package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import groovy.util.logging.Slf4j

/**
 * Retire a Pure GFS2 datastore: empty it with Morpheus's own storage migration, delete it in Morpheus, then remove
 * the LUN from the hosts and the array.
 *
 * Split of work (proven on Morpheus 9.1):
 *  - Morpheus-side changes run in the BROWSER as the logged-in user, over the same session-authenticated /api the UI
 *    uses (X-XSRF-TOKEN): flag hand-over (heartbeat target, VM secure metadata), drain (active=false), one
 *    Reconfigure per VM with the disk's datastoreId changed (core does virsh blockcopy + pivot, live), DELETE of the
 *    cluster datastore. Morpheus checks that user's permissions, and the tenant-token problem never arises.
 *  - This class does the read-only plan (all tenants' disks, the files actually on the mount, targets) and, after
 *    Morpheus has deleted the record, the host and array cleanup.
 */
@Slf4j
class RetireOps {
    /** Space a target must have beyond the moved data, as a fraction of the data. */
    static final double HEADROOM = 0.20
    /** Fraction of a target that must stay free after the move. */
    static final double KEEP_FREE = 0.10
    static final String LEFTOVER = HostScripts.LEFTOVER

    final MorpheusContext morpheus
    final PureClusterContext ctx
    final List<String> progress

    RetireOps(MorpheusContext morpheus, PureClusterContext ctx, List<String> progress = []) {
        this.morpheus = morpheus
        this.ctx = ctx
        this.progress = progress
    }

    private void step(String s) {
        progress << s
        log.info("[${ctx.cluster.name}] retire: ${s}")
    }

    // ------------------------------------------------------------------ pure decisions (unit-tested)

    /** Bytes a disk will take on the target: what qcow2 has allocated, else its full size. */
    static long footprint(Map disk) {
        ((disk.usedBytes ?: disk.maxBytes ?: 0) as Long)
    }

    /**
     * Candidate targets with fits/afterFree, best first; auto = the one with the most free space that fits.
     * @param candidates [id, name, typeCode, freeBytes, sizeBytes, active, online]
     */
    static Map chooseTarget(List<Map> candidates, long need) {
        long want = (long) Math.ceil(need * (1 + HEADROOM))
        List<Map> rows = candidates.collect { Map c ->
            long free = (c.freeBytes ?: 0) as Long
            long size = (c.sizeBytes ?: 0) as Long
            boolean usable = c.active != false && c.online != false
            boolean fits = usable && free >= want && (free - need) >= (long) (size * KEEP_FREE)
            c + [fits: fits, afterFree: free - need, reason: !usable ? 'inactive or offline' : (fits ? null : 'not enough free space')]
        }.sort { -(it.freeBytes ?: 0 as Long) }
        [need: need, needWithHeadroom: want, targets: rows, auto: rows.find { it.fits }]
    }

    /** Morpheus 9.1 HA heartbeat data on a heartbeat-target datastore (seen: mvm-hb/<cluster uuid>-<n>/<hash>/hb.properties). */
    static final String HEARTBEAT_DIR = /^mvm-hb\/.+/
    /** Morpheus 9.1 copy of a VM's libvirt definition beside its disks (seen: <vm>/<vm>.xml); stale once the VM moved off. */
    static final String VM_DEFINITION = /^([^\/]+)\/\1\.xml$/
    /** This plugin's restore-point index (SnapshotIndex) beside the data it describes. */
    static final String PLUGIN_DIR = /^\.pure-hvm\/.+/

    /**
     * Sorts the files on the mount:
     *  known     - a Morpheus disk on this datastore (StorageVolume.externalId = path under the mount)
     *  metadata  - Morpheus's own HA heartbeat data and VM definition copies, and this plugin's .pure-hvm/ index; deleted with the datastore once its
     *              disks and heartbeat role have moved
     *  leftovers - restore leftovers this plugin made (<disk>.pre-restore-<UTC>, <vm>.file-restore-<UTC>-<dev>.qcow2)
     *  unknown   - anything else (ISOs, NVRAM/TPM state, hand-copied files): blocks the delete
     */
    static Map classifyFiles(List<Map> files, Collection<String> knownPaths) {
        Set<String> known = knownPaths.findAll() as Set<String>
        Map<String, List<Map>> out = [known: [], metadata: [], leftovers: [], unknown: []]
        files.each { Map f ->
            String p = f.path as String
            if (known.contains(p)) {
                out.known << f
            } else if (p ==~ HEARTBEAT_DIR || p ==~ VM_DEFINITION || p ==~ PLUGIN_DIR) {
                out.metadata << f
            } else if (p ==~ /.*${LEFTOVER}/) {
                out.leftovers << f
            } else {
                out.unknown << f
            }
        }
        out
    }

    static List<Map> parseFiles(HostRunner.Result r) {
        r.all('FILE').collect { String l ->
            int i = l.lastIndexOf('|')
            [path: l.substring(0, i), bytes: l.substring(i + 1).isLong() ? l.substring(i + 1) as Long : null]
        }
    }

    // ------------------------------------------------------------------ plan (read-only)

    /** VMs of this cluster with their disks on {@code datastoreId}: [serverId, instanceId, name, power, disks[]]. */
    List<Map> vmsOn(Long datastoreId) {
        ctx.vms.collect { ComputeServer listed ->
            ComputeServer s = morpheus.services.computeServer.get(listed.id) ?: listed
            List<Map> disks = (s.volumes ?: []).findAll { StorageVolume v -> v.datastore?.id == datastoreId }.collect { StorageVolume v ->
                [volumeId: v.id, name: v.name, path: v.externalId, maxBytes: v.maxStorage, usedBytes: v.usedStorage, root: v.rootVolume]
            }
            disks ? [serverId: s.id, instanceId: instanceIdOf(s.id), name: s.name, power: s.powerState?.toString(), disks: disks] : null
        }.findAll()
    }

    private Long instanceIdOf(Long serverId) {
        try {
            morpheus.services.workload.list(new DataQuery().withFilter('server.id', serverId)).find()?.instance?.id
        } catch (Exception e) {
            log.debug("no workload for server ${serverId}: ${e.message}")
            null
        }
    }

    /** Morpheus volumes on the datastore that no VM of this cluster holds (detached disks, other clusters' VMs). */
    List<Map> orphanVolumes(Long datastoreId, Collection<Long> heldVolumeIds) {
        morpheus.services.storageVolume.list(new DataQuery().withFilter('datastore.id', datastoreId))
                .findAll { !heldVolumeIds.contains(it.id) }
                .collect { [volumeId: it.id, name: it.name, path: it.externalId, maxBytes: it.maxStorage] }
    }

    /** Shared datastores of this cluster that can take the disks (GFS2 or NFS; never a host-local directory pool). */
    List<Map> candidates(Long excludeId) {
        ctx.datastores.findAll { Datastore d -> d.id != excludeId && ((d.datastoreType?.code ?: '') =~ /(?i)gfs2|nfs/) }
                .collect { Datastore d ->
                    [id: d.id, name: d.name, typeCode: d.datastoreType?.code, freeBytes: d.freeSpace, sizeBytes: d.storageSize,
                     active: d.active, online: d.online, pure: PureNames.serialFromWwid(d.getConfigProperty('blockDevice') as String) != null]
                }
    }

    /** The linked array volume behind the datastore and whether this cluster owns it (plugin tags). */
    Map arrayVolume(Datastore ds) {
        String serial = PureNames.serialFromWwid(ds.getConfigProperty('blockDevice') as String)
        if (!serial) {
            return [error: 'not a FlashArray LUN']
        }
        for (Map l : ctx.links) {
            Long sid = l.storageServerId as Long
            Map found = ctx.withArray(sid) { FlashArrayClient fa ->
                Map v = fa.volumeBySerial(serial)
                if (!v) {
                    return null
                }
                Map<String, String> tags = fa.volumeTags(v.name as String)
                [storageServerId: sid, server: ctx.storageServer(sid).name, volume: v.name, serial: serial,
                 wwid: PureNames.wwid(serial), provisioned: v.provisioned, owned: PureNames.ownedBy(tags, ctx.cluster.id),
                 taggedDatastoreId: tags['morpheus-datastore-id']]
            }
            if (found) {
                return found
            }
        }
        [serial: serial, wwid: PureNames.wwid(serial), error: 'LUN not found on any linked array']
    }

    /**
     * Files on the mount, from the first host that has it mounted. A host whose listing FAILS is not "not mounted":
     * if no host could list it and any failed, this throws, so an unreadable datastore is never taken for an empty one.
     */
    Map filesOn(String mountPath) {
        if (!mountPath) {
            return [mounted: false, files: []]
        }
        HostRunner r = ctx.runner
        List<String> failed = []
        for (String h : ctx.hostNames) {
            HostRunner.Result res = r.run(h, HostScripts.listFiles(mountPath))
            if (!res.ok) {
                failed << "${h}: ${res.error}".toString()
            } else if (res.kv.MOUNTED == '1') {
                return [mounted: true, host: h, count: res.kv.COUNT as Integer, truncated: (res.kv.COUNT as Integer) > HostScripts.LIST_FILES_MAX,
                        files: parseFiles(res)]
            }
        }
        if (failed) {
            throw new IllegalStateException("Could not list the files on ${mountPath} (${failed.join('; ')})")
        }
        [mounted: false, files: []]
    }

    Map plan(Long datastoreId, Collection<String> openRecoveryVolumes = []) {
        Datastore ds = ctx.datastore(datastoreId)
        Map arr = arrayVolume(ds)
        List<Map> vms = vmsOn(datastoreId)
        List<Map> disks = vms.collectMany { it.disks as List<Map> }
        List<Map> orphans = orphanVolumes(datastoreId, disks*.volumeId as List<Long>)
        Map files = filesOn(ds.externalPath)
        Map sorted = classifyFiles(files.files as List<Map>, (disks + orphans)*.path as List<String>)
        long need = (disks.sum { footprint(it as Map) } ?: 0L) as long
        Map choice = chooseTarget(candidates(datastoreId), need)

        List<String> blockers = []
        List<String> warnings = []
        if (arr.error) {
            blockers << "Array side: ${arr.error}".toString()
        } else if (!arr.owned) {
            blockers << "${arr.volume} is not tagged for this cluster: adopt the datastore first".toString()
        }
        if (arr.volume && openRecoveryVolumes.contains(arr.volume)) {
            blockers << 'A recovery session is open on this datastore: close it first'
        }
        if (disks && !choice.auto) {
            blockers << "No other shared datastore on this cluster has room for ${need} bytes (+${(int) (HEADROOM * 100)}% headroom)".toString()
        }
        if (orphans) {
            warnings << "${orphans.size()} Morpheus volume(s) on this datastore belong to no VM of this cluster; Retire cannot move them and will stop before deleting".toString()
        }
        if (sorted.unknown) {
            warnings << "${(sorted.unknown as List).size()} file(s) on the datastore are not Morpheus disks; Retire will stop before deleting until they are removed".toString()
        }
        if (files.truncated) {
            warnings << "Only the first ${HostScripts.LIST_FILES_MAX} of ${files.count} files are listed".toString()
        }
        vms.findAll { !it.instanceId }.each { warnings << "${it.name} has no Morpheus instance; it will be moved with the server-level Reconfigure (not yet tested)".toString() }

        [datastore: [id: ds.id, name: ds.name, active: ds.active, online: ds.online, heartbeatTarget: ds.heartbeatTarget,
                     sizeBytes: ds.storageSize, freeBytes: ds.freeSpace, mountPath: ds.externalPath],
         array    : arr, vms: vms, orphans: orphans, files: [mounted: files.mounted, host: files.host, count: files.count] + sorted,
         need     : need, targets: choice.targets, auto: choice.auto?.id, blockers: blockers, warnings: warnings,
         empty    : !disks && !orphans]
    }

    // ------------------------------------------------------------------ Morpheus record changes (in-process)

    /**
     * Datastore-record changes Retire needs, done in-process: on 9.1 a tenant user may READ the datastores of a
     * cluster whose cloud the master tenant owns, but any field change through /api is refused (403), and a master
     * token gets "Cluster not found" on the same endpoint (tenant scoping, as with create). The plugin's Full
     * permission gates these; the caller logs who asked.
     */
    String morpheusAction(String action, Long datastoreId, Long targetId = null) {
        Datastore ds = ctx.datastore(datastoreId)
        // a tenant user's own /api edit of a datastore strips its tenant entry (seen on 9.1); repair while here
        DatastoreTenancy.ensureOwnerPermission(morpheus, ds)
        switch (action) {
            case 'drain':
                ds.active = false
                morpheus.services.cloud.datastore.save(ds)
                return "${ds.name}: drained (active=false, Morpheus places no new disks on it)".toString()
            case 'undrain':
                ds.active = true
                morpheus.services.cloud.datastore.save(ds)
                return "${ds.name}: active again".toString()
            case 'heartbeat-handover':
                Datastore t = ctx.datastore(targetId)
                if (t.id == ds.id) {
                    throw new IllegalArgumentException('Target is the datastore being retired')
                }
                t.heartbeatTarget = true
                morpheus.services.cloud.datastore.save(t)
                return "${t.name}: now an HA heartbeat target".toString()
            case 'heartbeat-clear':
                ds.heartbeatTarget = false
                morpheus.services.cloud.datastore.save(ds)
                return "${ds.name}: no longer an HA heartbeat target".toString()
            default:
                throw new IllegalArgumentException("Unknown action ${action}")
        }
    }

    /** Deletes the Morpheus datastore through core's datastore-type service, after re-proving it is empty. */
    private void deleteInMorpheus(Long datastoreId, boolean allowLeftovers) {
        Datastore ds = morpheus.services.cloud.datastore.get(datastoreId)
        if (ds == null) {
            step("Morpheus datastore ${datastoreId} already deleted")
            return
        }
        if (ds.heartbeatTarget) {
            throw new IllegalStateException("${ds.name} is still an HA heartbeat target; hand that over first")
        }
        Map q = plan(datastoreId)
        Map f = q.files as Map
        if (!q.empty || f.known || f.unknown || (f.leftovers && !allowLeftovers)) {
            throw new IllegalStateException("${ds.name} is not empty: ${q.vms.size()} VM(s), ${(q.orphans as List).size()} other volume(s), " +
                    "${(f.known as List).size()} disk file(s), ${(f.unknown as List).size()} other file(s), ${(f.leftovers as List).size()} restore leftover(s)")
        }
        step("deleting ${ds.name} in Morpheus (core unmounts it and removes its storage pools on the hosts)")
        def r = MorpheusSession.run(morpheus) { morpheus.services.storage.datastoreType.removeDatastore(ds) }
        if (r instanceof com.morpheusdata.response.ServiceResponse && !r.success) {
            throw new IllegalStateException("Morpheus refused to delete ${ds.name}: ${r.msg ?: r.errors}")
        }
        if (morpheus.services.cloud.datastore.get(datastoreId) != null) {
            // core's service did the host side; make sure the record goes too
            morpheus.services.cloud.datastore.remove(morpheus.services.cloud.datastore.get(datastoreId))
            step("removed the Morpheus datastore record ${datastoreId}")
        }
    }

    // ------------------------------------------------------------------ finish (after Morpheus deleted the record)

    /**
     * Host and array cleanup once Morpheus has deleted the datastore record. Re-runnable: the volume is identified by
     * the plugin's tags (cluster id + Morpheus datastore id), which survive the Morpheus delete.
     */
    Map finish(FlashArrayClient fa, Long datastoreId, String volume, boolean allowLeftovers, int waitSeconds = 600, boolean deleteFirst = false) {
        Map<String, String> tags = fa.volumeTags(volume)
        if (!PureNames.ownedBy(tags, ctx.cluster.id) || tags['morpheus-datastore-id'] != datastoreId as String) {
            throw new IllegalStateException("${volume} is not tagged as datastore ${datastoreId} of cluster ${ctx.cluster.name}; refusing to touch it")
        }
        // the mount path is on the Morpheus record, which the delete removes: read it first (for the pool cleanup)
        String mountPath = morpheus.services.cloud.datastore.get(datastoreId)?.externalPath
        if (deleteFirst) {
            deleteInMorpheus(datastoreId, allowLeftovers)
        }
        Map v = fa.getVolume(volume)
        if (!v) {
            throw new IllegalStateException("${volume} not found on the array")
        }
        String wwid = PureNames.wwid(v.serial as String)

        step("waiting for Morpheus to finish deleting datastore ${datastoreId}")
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (morpheus.services.cloud.datastore.get(datastoreId) != null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Morpheus still has datastore ${datastoreId} after ${waitSeconds}s; nothing on the array was changed")
            }
            sleep(10000)
        }
        step('Morpheus datastore record is gone')

        Map<String, HostRunner.Result> state = ctx.onAllHosts(HostScripts.storageState([wwid]))
        Map<String, String> mountedAt = state.collectEntries { h, res ->
            List<String> p = (res.all('STORAGE').find { it.startsWith(wwid + '|') } ?: '').split(/\|/, -1) as List<String>
            [(h): p.size() > 2 && p[2] ? p[2] : null]
        }.findAll { it.value } as Map<String, String>
        if (mountedAt) {
            // Morpheus left it mounted: only an empty filesystem (or allowed leftovers) may be unmounted here
            String mp = mountedAt.values().first()
            Map files = filesOn(mp)
            Map sorted = classifyFiles(files.files as List<Map>, [])
            if (sorted.unknown || (sorted.leftovers && !allowLeftovers)) {
                throw new IllegalStateException("Morpheus deleted the record but ${mp} is still mounted on ${mountedAt.keySet().join(', ')} " +
                        "and holds ${files.count} file(s); nothing on the array was changed")
            }
            step("Morpheus left ${mp} mounted on ${mountedAt.keySet().join(', ')}; unmounting the empty filesystem".toString())
        }

        Map<String, HostRunner.Result> clean = ctx.onAllHosts(HostScripts.hostCleanup(wwid, mountedAt.values().find()))
        List<String> dirty = clean.findAll { h, res -> !res.ok }.collect { h, res -> "${h}: ${res.error}".toString() }
        if (dirty) {
            throw new IllegalStateException("Host cleanup failed (${dirty.join('; ')}); the LUN stays connected so nothing is lost")
        }
        step("LUN ${wwid} removed from ${ctx.hostNames.size()} host(s)".toString())

        mountPath = mountPath ?: mountedAt.values().find()
        if (mountPath ==~ HostScripts.GFS2_MOUNT_PATH) {
            // core's removeDatastore may leave the libvirt pool defined on the hosts; it points at an empty directory now
            ctx.onAllHosts(HostScripts.removePools(mountPath)).each { String h, HostRunner.Result res ->
                res.all('POOL_REMOVED').each { step("${h}: removed libvirt pool ${it}".toString()) }
                res.all('WARN').each { step("${h}: ${it}".toString()) }
            }
        }

        fa.tagVolume(volume, PureNames.tagNamespace, ['retired': new Date().toInstant().toString()])
        fa.disconnectEverywhere(volume)
        step("disconnected ${volume} from every host and host group".toString())
        fa.destroyVolume(volume)
        step("destroyed ${volume}: it stays in the array's destroyed bucket (recoverable until the array eradicates it) - not eradicated".toString())
        [volume: volume, wwid: wwid, destroyed: true, eradicated: false]
    }
}
