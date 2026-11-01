package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import groovy.util.logging.Slf4j

/**
 * Space nobody is using on the cluster's GFS2 datastores.
 *  - restore leftovers (this plugin's): <disk>.pre-restore-<UTC>, the disk an in-place restore replaced (kept as the
 *    undo), and file-restore copies the sweeper has not removed. Deletable here, once no host's libvirt uses the file.
 *  - other files that are not Morpheus disks (ISOs, hand-copied images) and Morpheus disks no VM of this cluster holds:
 *    shown only. They may be someone's data, and Morpheus disks are deleted in Morpheus.
 */
@Slf4j
class Reclaim {
    static final int SHOW_OTHER = 20

    final MorpheusContext morpheus
    final PureClusterContext ctx
    final List<String> progress

    Reclaim(MorpheusContext morpheus, PureClusterContext ctx, List<String> progress = []) {
        this.morpheus = morpheus
        this.ctx = ctx
        this.progress = progress
    }

    /** GFS2 datastores of the cluster (mounted at /mnt/<uuid>). */
    List<Datastore> gfs2Datastores() {
        ctx.datastores.findAll { (it.externalPath ?: '') ==~ HostScripts.GFS2_MOUNT_PATH }
    }

    /** What each leftover is, from its name: [kind, since (epoch ms, from the UTC stamp in the name)]. */
    static Map kindOf(String path) {
        def pre = path =~ /\.pre-restore-(\d{8,14})$/
        if (pre.find()) {
            return [kind: 'pre-restore', since: stamp(pre.group(1))]
        }
        def copy = path =~ /\.${HostScripts.FILE_RESTORE_TAG}-(\d{14})-[a-z]+\.qcow2$/
        copy.find() ? [kind: 'file-restore copy', since: stamp(copy.group(1))] : [kind: 'unknown']
    }

    private static Long stamp(String s) {
        try {
            String full = s.padRight(14, '0')
            java.time.LocalDateTime.parse(full, java.time.format.DateTimeFormatter.ofPattern('yyyyMMddHHmmss'))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (Exception ignored) {
            null
        }
    }

    /**
     * Every host's view of the given mounts: leftovers (from the first host that has each mount), the files any host's
     * libvirt uses, and the host each mount was read from. A host whose scan fails makes "in use" unknown: refuse.
     */
    Map scanHosts(List<String> mounts) {
        Map<String, HostRunner.Result> res = ctx.onAllHosts(HostScripts.leftoverScan(mounts))
        List<String> failed = res.findAll { h, r -> !r.ok }.collect { h, r -> "${h}: ${r.error}".toString() }
        if (failed) {
            throw new IllegalStateException("Could not read every host, so whether a file is in use is unknown (${failed.join('; ')})")
        }
        Set<String> inUse = res.values().collectMany { it.all('INUSE') } as Set<String>
        Map<String, String> readFrom = [:]
        Map<String, List<Map>> left = [:]
        res.each { String h, HostRunner.Result r ->
            r.all('MOUNTED').each { String mp ->
                if (readFrom.containsKey(mp)) {
                    return
                }
                readFrom[mp] = h
                left[mp] = r.all('LEFT').findAll { it.startsWith(mp + '|') }.collect { String l ->
                    List<String> p = l.split(/\|/, -1) as List<String>
                    [path: p[1], bytes: p[2]?.isLong() ? p[2] as Long : null, mtime: p[3] ? ((p[3].tokenize('.')[0] ?: '0') as Long) * 1000L : null]
                }.findAll { (it.path as String) ==~ /.*${HostScripts.LEFTOVER}/ }.collect { Map f ->
                    f + kindOf(f.path as String) + [inUse: inUse.contains("${mp}/${f.path}".toString())]
                }
            }
        }
        [inUse: inUse, readFrom: readFrom, leftovers: left]
    }

    /** Read-only report per GFS2 datastore. */
    List<Map> scan() {
        List<Datastore> ds = gfs2Datastores()
        if (!ds) {
            return []
        }
        Map hosts = scanHosts(ds*.externalPath as List<String>)
        Set<Long> held = heldVolumeIds()
        RetireOps ro = new RetireOps(morpheus, ctx)
        ds.collect { Datastore d ->
            String mp = d.externalPath
            List<Map> leftovers = (hosts.leftovers[mp] ?: []) as List<Map>
            Map out = [datastoreId: d.id, name: d.name, mount: mp, mounted: hosts.readFrom.containsKey(mp), host: hosts.readFrom[mp],
                       sizeBytes: d.storageSize, freeBytes: d.freeSpace,
                       leftovers: leftovers.sort { -((it.bytes ?: 0) as Long) },
                       reclaimableBytes: (leftovers.findAll { !it.inUse }.sum { (it.bytes ?: 0) as Long } ?: 0L)]
            try {
                List<Map> detached = ro.orphanVolumes(d.id, held)
                Map files = ro.filesOn(mp)
                // StorageVolume.externalId is the disk's path under the mount: every Morpheus disk, held or not, is known
                List<String> known = morpheus.services.storageVolume.list(new DataQuery().withFilter('datastore.id', d.id))*.externalId as List<String>
                List<Map> other = RetireOps.classifyFiles(files.files as List<Map>, known).unknown as List<Map>
                out += [detachedDisks: detached, otherFiles: other.sort { -((it.bytes ?: 0) as Long) }.take(SHOW_OTHER), otherCount: other.size(),
                        otherBytes: (other.sum { (it.bytes ?: 0) as Long } ?: 0L), truncated: files.truncated ?: false]
            } catch (Exception e) {
                out.error = e.message
            }
            out
        }
    }

    /** Morpheus volume ids held by a VM of this cluster. */
    private Set<Long> heldVolumeIds() {
        ctx.vms.collectMany { ComputeServer listed ->
            ((morpheus.services.computeServer.get(listed.id) ?: listed).volumes ?: [])*.id
        } as Set<Long>
    }

    /**
     * Deletes the given leftovers (paths under the datastore's mount) after checking again, on every host, that each is
     * still a leftover nobody uses. Run as a cluster job, so no restore is creating or reading one meanwhile.
     */
    Map delete(Long datastoreId, List<String> paths) {
        Datastore d = ctx.datastore(datastoreId)
        String mp = d.externalPath
        if (!(mp ==~ HostScripts.GFS2_MOUNT_PATH)) {
            throw new IllegalArgumentException("${d.name} is not a GFS2 datastore")
        }
        if (!paths) {
            throw new IllegalArgumentException('Choose the files to delete')
        }
        Map hosts = scanHosts([mp])
        String host = hosts.readFrom[mp]
        if (!host) {
            throw new IllegalStateException("${d.name} is not mounted on any host")
        }
        Map<String, Map> now = ((hosts.leftovers[mp] ?: []) as List<Map>).collectEntries { [(it.path): it] }
        List<String> skipped = []
        List<String> go = paths.unique().findAll { String p ->
            Map f = now[p]
            String why = f == null ? 'no longer there, or not a restore leftover' : (f.inUse ? 'in use by a VM' : null)
            if (why) {
                skipped << "${p}: ${why}".toString()
                progress << "skipped ${p}: ${why}".toString()
            }
            !why
        }
        List<String> removed = []
        long freed = 0
        if (go) {
            progress << "deleting ${go.size()} file(s) from ${d.name} on ${host}".toString()
            HostRunner.Result r = ctx.runner.run(host, HostScripts.removeLeftovers(mp, go))
            if (!r.ok) {
                throw new IllegalStateException("${host}: ${r.error}")
            }
            removed = r.all('REMOVED')
            removed.each { String p ->
                freed += (now[p]?.bytes ?: 0) as Long
                progress << "deleted ${p} (${now[p]?.kind}, ${gib(now[p]?.bytes as Long)})".toString()
            }
            (r.all('INUSE').collect { "${it}: in use by a VM on ${host}" } + r.all('GONE').collect { "${it}: already gone" } +
                    r.all('FAILED').collect { "${it}: delete failed" }).each { skipped << it.toString(); progress << "skipped ${it}".toString() }
        }
        progress << "freed ${gib(freed)} on ${d.name}".toString()
        [datastore: d.name, removed: removed, skipped: skipped, freedBytes: freed]
    }

    private static String gib(Long b) {
        String.format('%.1f GiB', (b ?: 0L) / (1L << 30) as double)
    }
}
