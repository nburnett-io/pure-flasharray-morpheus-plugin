package org.purehvm.ops

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import groovy.util.logging.Slf4j

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Server-side orchestration of the GFS2 datastore lifecycle on a FlashArray-backed HVM cluster.
 * Unlike Pure's vSphere plugin (where the browser sequences the steps), each operation here runs end to end
 * in the plugin, rolls back what it created on failure, and reports progress through {@code log}.
 */
@Slf4j
class DatastoreOps {
    static final long MIN_BYTES = 1L << 30            // 1 GiB; GFS2 with 32 journals needs headroom anyway
    static final int PARALLEL_HOSTS = 8

    final FlashArrayClient fa
    final HostRunner runner
    final List<String> hosts
    final Gfs2Registrar registrar
    final List<String> progress

    DatastoreOps(FlashArrayClient fa, HostRunner runner, List<String> hosts, Gfs2Registrar registrar, List<String> progress = []) {
        this.fa = fa
        this.runner = runner
        this.hosts = hosts
        this.registrar = registrar
        this.progress = progress
    }

    private void step(String s) {
        progress << s
        log.info(s)
    }

    /** Run a script on every host in parallel; returns host -> result. */
    Map<String, HostRunner.Result> onAllHosts(String script) {
        onAllHosts(runner, hosts, script)
    }

    static Map<String, HostRunner.Result> onAllHosts(HostRunner runner, List<String> hosts, String script) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_HOSTS, Math.max(1, hosts.size())))
        try {
            Map<String, java.util.concurrent.Future<HostRunner.Result>> fut = hosts.collectEntries { String h ->
                [(h): pool.submit({ runner.run(h, script) } as Callable<HostRunner.Result>)]
            }
            fut.collectEntries { h, f -> [(h): f.get()] } as Map<String, HostRunner.Result>
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Every host must see the device at the expected size with at least one healthy path; returns problems.
     */
    List<String> verifyVisible(Map<String, HostRunner.Result> res, long expectBytes) {
        res.collect { String h, HostRunner.Result r ->
            long size = (r.kv.SIZE ?: '0') as Long
            int paths = (r.kv.PATHS ?: '0') as Integer
            if (!r.ok) {
                return "${h}: ${r.error}".toString()
            }
            if (size == 0) {
                return "${h}: device not visible".toString()
            }
            if (size != expectBytes) {
                return "${h}: size ${size} != ${expectBytes}".toString()
            }
            paths < 1 ? "${h}: no healthy paths".toString() : null
        }.findAll()
    }

    // ------------------------------------------------------------------ provision

    static class Provisioned {
        String volume
        String serial
        String devicePath
        Long datastoreId
    }

    Provisioned provisionGfs2(Long clusterId, String hostGroup, String datastoreName, long bytes) {
        String vol = PureNames.sanitize(datastoreName)
        if (!PureNames.isValidName(vol)) {
            throw new IllegalArgumentException("Cannot derive a valid volume name from ${datastoreName}")
        }
        if (bytes < MIN_BYTES) {
            throw new IllegalArgumentException('Datastore must be at least 1 GiB')
        }
        bytes = (bytes >> 9) << 9
        if (fa.volumeNameTaken(vol)) {
            throw new IllegalStateException("Volume ${vol} already exists on the array (including the destroyed bucket)")
        }
        Provisioned p = new Provisioned(volume: vol)
        boolean connected = false
        try {
            Map v = fa.createVolume(vol, bytes)
            p.serial = v.serial as String
            p.devicePath = PureNames.devicePath(p.serial)
            step("created volume ${vol} (${bytes} bytes, serial ${p.serial})")
            fa.tagVolume(vol, PureNames.tagNamespace, ['morpheus-cluster-id': clusterId as String, 'role': 'gfs2-datastore', 'datastore-name': datastoreName])

            fa.connectVolumeToHostGroup(vol, hostGroup)
            connected = true
            step("connected ${vol} to host group ${hostGroup}")

            String wwid = PureNames.wwid(p.serial)
            List<String> problems = verifyVisible(onAllHosts(HostScripts.rescan(wwid)), bytes)
            if (problems) {
                throw new IllegalStateException("LUN not visible on every host: ${problems.join('; ')}")
            }
            step("${p.devicePath} visible on all ${hosts.size()} hosts")

            p.datastoreId = registrar.register(clusterId, datastoreName, p.devicePath)
            fa.tagVolume(vol, PureNames.tagNamespace, ['morpheus-datastore-id': p.datastoreId as String])
            step("Morpheus GFS2 datastore ${datastoreName} created (id ${p.datastoreId})")
            return p
        } catch (Exception e) {
            step("FAILED: ${e.message}; rolling back")
            rollbackNewVolume(p, connected)
            throw e
        }
    }

    /** A volume this operation just created holds no data: remove it from hosts, then the array, then eradicate. */
    private void rollbackNewVolume(Provisioned p, boolean connected) {
        try {
            if (p.serial && connected) {
                // Host side first: disconnecting on the array first leaves stale maps on Ubuntu 22.04/24.04.
                Map<String, HostRunner.Result> res = onAllHosts(HostScripts.hostCleanup(PureNames.wwid(p.serial)))
                List<String> dirty = res.findAll { h, r -> !r.ok }.collect { h, r -> "${h}: ${r.error}".toString() }
                if (dirty) {
                    step("ROLLBACK STOPPED: hosts not clean (${dirty.join('; ')}); volume ${p.volume} left connected")
                    return
                }
                fa.disconnectEverywhere(p.volume)
            }
            if (p.serial) {
                fa.destroyVolume(p.volume)
                fa.eradicateVolume(p.volume)
                step("rolled back volume ${p.volume}")
            }
        } catch (Exception re) {
            step("ROLLBACK INCOMPLETE for ${p.volume}: ${re.message}")
        }
    }

    // ------------------------------------------------------------------ grow

    /** Pure resize -> every host rescans and resizes its map -> all agree -> gfs2_grow once -> Morpheus refresh. */
    Map grow(Long clusterId, String volume, long newBytes) {
        Map v = fa.getVolume(volume)
        if (!v) {
            throw new IllegalArgumentException("No volume ${volume}")
        }
        newBytes = (newBytes >> 9) << 9
        long current = v.provisioned as Long
        if (newBytes <= current) {
            throw new IllegalArgumentException("New size must exceed the current ${current} bytes (GFS2 cannot shrink)")
        }
        String wwid = PureNames.wwid(v.serial as String)
        fa.resizeVolume(volume, newBytes)
        step("array volume ${volume} grown to ${newBytes} bytes")

        Map<String, HostRunner.Result> res = onAllHosts(HostScripts.resize(wwid))
        List<String> problems = res.findAll { h, r -> !r.ok || ((r.kv.SIZE ?: '0') as Long) != newBytes }
                .collect { h, r -> "${h}: ${r.error ?: 'size ' + r.kv.SIZE}".toString() }
        if (problems) {
            throw new IllegalStateException("Not every host sees the new size; gfs2_grow NOT run: ${problems.join('; ')}")
        }
        String growHost = res.find { h, r -> r.kv.MOUNT }?.key
        if (!growHost) {
            throw new IllegalStateException('Datastore is not mounted on any host')
        }
        HostRunner.Result g = runner.run(growHost, HostScripts.gfs2Grow(res[growHost].kv.MOUNT))
        if (!g.ok) {
            throw new IllegalStateException("gfs2_grow failed on ${growHost}: ${g.error}")
        }
        step("gfs2_grow on ${growHost}: filesystem now ${g.kv.FSSIZE} bytes")
        // Updating Morpheus' capacity figures is the caller's job (MorpheusCapacity), and its failure is not a failed grow.
        [volume: volume, bytes: newBytes, fsBytes: g.kv.FSSIZE as Long, host: growHost]
    }
}
