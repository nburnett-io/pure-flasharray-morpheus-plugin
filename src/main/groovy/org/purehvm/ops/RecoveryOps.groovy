package org.purehvm.ops

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import groovy.util.logging.Slf4j

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * FlashArray snapshot recovery for GFS2 datastores WITHOUT touching the production volume. The production
 * datastore is only ever read (never overwritten); restores land as NEW files or a NEW datastore.
 *
 *  - Recovery session (workflow i): snapshot -> new volume -> ONE host -> lock_nolock read-only mount
 *    under a private lock table -> browse / copy disks out -> close (host cleanup, then array).
 *  - Clone as datastore (workflow ii): snapshot -> new volume -> ONE host -> re-signature (new lock table
 *    + UUID, journals replayed) -> host group -> every host -> Morpheus adopts it as a new GFS2 datastore.
 *
 * Pure's vSphere plugin does the VMFS equivalent (copy snapshot, resignature, register, svMotion back).
 */
@Slf4j
class RecoveryOps {
    static final SecureRandom RNG = new SecureRandom()
    /** UTC stamp of restore leftovers (<disk>.pre-restore-<stamp>) and file-restore copies; HostScripts checks this form. */
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern('yyyyMMddHHmmss').withZone(ZoneOffset.UTC)

    final FlashArrayClient fa
    final HostRunner runner
    final List<String> progress

    RecoveryOps(FlashArrayClient fa, HostRunner runner, List<String> progress = []) {
        this.fa = fa
        this.runner = runner
        this.progress = progress
    }

    private void step(String s) {
        progress << s
        log.info(s)
    }

    /** Where a FlashArray datastore is mounted on a host; throws when it is not. */
    static String mountOf(HostRunner runner, String host, String wwid) {
        String mp = runner.run(host, HostScripts.findMount(wwid)).kv.MOUNT
        if (!mp) {
            throw new IllegalStateException("Production datastore is not mounted on ${host}")
        }
        mp
    }

    static String randomTag() {
        String alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
        (1..6).collect { alphabet[RNG.nextInt(alphabet.length())] }.join('')
    }

    // ------------------------------------------------------------------ snapshots

    List<Map> snapshots(String volume) {
        fa.listVolumeSnapshots(volume).collect { Map s ->
            [name: s.name, created: s.created, provisioned: s.provisioned, serial: s.serial,
             protectionGroup: (s.name as String).count('.') >= 2 ? (s.name as String).tokenize('.')[0] : null]
        }.sort { -((it.created ?: 0) as Long) }
    }

    String snapshot(String volume, String suffix = null) {
        if (suffix && !(suffix ==~ /[A-Za-z0-9-]{1,63}/ && suffix =~ /[A-Za-z]/)) {
            throw new IllegalArgumentException('Snapshot suffix: 1-63 letters, digits or -, with at least one letter')
        }
        String name = fa.createVolumeSnapshot([volume], suffix).find()?.name
        step("snapshot ${name} created")
        name
    }

    // ------------------------------------------------------------------ recovery session (workflow i)

    static class Session {
        String tag
        String volume          // temporary clone volume
        String serial
        String wwid
        String host            // Morpheus host name the clone is mounted on
        String arrayHost       // FlashArray host object for that node
        String mount
        String sourceVolume
        String snapshot
        List<Map> files = []   // [path, size, mtime]
    }

    /** FILE=<bytes>|<mtime>|<path> lines from recoveryMount / recoveryFiles. */
    static List<Map> parseFiles(HostRunner.Result r) {
        r.all('FILE').collect { String f ->
            List<String> p = f.split(/\|/, 3) as List<String>
            [size: p[0] as Long, mtime: (p[1] as BigDecimal).longValue(), path: p[2]]
        }
    }

    /**
     * @param clusterId tagged on the clone so the session can be rebuilt after a plugin reload
     * @param expires epoch ms after which the sweeper closes the session (tag; "Keep open" moves it)
     */
    Session openRecovery(String sourceVolume, String snapshotName, String host, String arrayHost, Long clusterId = null, Long expires = null) {
        if (!snapshotName.startsWith(sourceVolume + '.') && !snapshotName.endsWith('.' + sourceVolume)) {
            throw new IllegalArgumentException("${snapshotName} is not a snapshot of ${sourceVolume}")
        }
        Session s = new Session(tag: randomTag(), sourceVolume: sourceVolume, snapshot: snapshotName, host: host, arrayHost: arrayHost)
        s.volume = PureNames.sanitize("${sourceVolume.take(48)}-rcv-${s.tag}")
        boolean connected = false
        try {
            Map v = fa.copyVolume(snapshotName, s.volume)
            s.serial = v.serial
            s.wwid = PureNames.wwid(s.serial)
            Map<String, String> tags = [role: 'recovery-clone', source: sourceVolume, snapshot: snapshotName, host: host,
                                        created: System.currentTimeMillis() as String, tag: s.tag]
            if (clusterId != null) {
                tags['recovery-cluster-id'] = clusterId as String
            }
            if (expires != null) {
                tags.expires = expires as String
            }
            fa.tagVolume(s.volume, PureNames.tagNamespace, tags)
            step("cloned ${snapshotName} to ${s.volume}")
            fa.connectVolumeToHost(s.volume, arrayHost)
            connected = true
            step("connected ${s.volume} to ${arrayHost} only")
            HostRunner.Result r = runner.run(host, HostScripts.recoveryMount(s.wwid, s.tag))
            if (!r.ok) {
                throw new IllegalStateException("recovery mount on ${host} failed: ${r.error}")
            }
            if ((r.kv.PR_KEYS ?: '0') != '0') {
                step("warning: clone carries ${r.kv.PR_KEYS} SCSI-3 reservation key(s)")
            }
            s.mount = r.kv.MOUNT
            s.files = parseFiles(r)
            step("mounted read-only at ${host}:${s.mount} (${s.files.size()} disk file(s), source lock table ${r.kv.SOURCE_LOCKTABLE})")
            return s
        } catch (Exception e) {
            step("FAILED: ${e.message}; cleaning up")
            closeQuietly(s, connected)
            throw e
        }
    }

    /** Copy one disk from the recovery mount to the production datastore as a NEW file under restored/. */
    Map restoreDisk(Session s, String sourcePath, String productionWwid, String destName) {
        if (!(destName ==~ /[A-Za-z0-9._-]{1,128}/) || destName.startsWith('.')) {
            throw new IllegalArgumentException('Destination file name: letters, digits, . _ - only')
        }
        if (!s.files.any { it.path == sourcePath }) {
            throw new IllegalArgumentException("${sourcePath} is not a disk in this recovery session")
        }
        String prodMount = mountOf(runner, s.host, productionWwid)
        String dest = "${prodMount}/restored/${destName}".toString()
        HostRunner.Result r = runner.run(s.host, HostScripts.restoreDisk(sourcePath, s.mount, prodMount, dest))
        if (!r.ok) {
            throw new IllegalStateException("restore failed: ${r.error}")
        }
        step("restored ${sourcePath} -> ${dest} (${r.kv.SIZE} bytes, qemu-img check passed)")
        [dest: dest, size: r.kv.SIZE]
    }

    // ------------------------------------------------------------------ whole-VM restore from a recovery session

    /**
     * For each VM ([serverId, name, domain, host, power]) asks libvirt on its host for its file disks, keeps those on
     * the production datastore, and checks which exist in the snapshot. Returns only VMs with disks on this datastore.
     */
    List<Map> vmsInSession(Session s, String prodMount, List<Map> vms) {
        List<Map> out = []
        vms.each { Map vm ->
            HostRunner.Result r = runner.run(vm.host as String, HostScripts.vmDisks(vm.domain as String))
            if (!r.ok) {
                return
            }
            List<Map> disks = r.pairs('DISK').collect { List<String> p -> [target: p[0], path: p[1]] }.findAll { (it.path as String).startsWith(prodMount + '/') }
            if (!disks) {
                return
            }
            disks.each { it.snapPath = s.mount + (it.path as String).substring(prodMount.length()) }
            HostRunner.Result ex = runner.run(s.host, HostScripts.existing(disks*.snapPath as List<String>))
            Set<String> present = ex.all('EXISTS') as Set<String>
            disks.each { it.inSnapshot = present.contains(it.snapPath) }
            out << (vm + [domainState: r.kv.STATE, disks: disks, restorable: disks.every { it.inSnapshot }])
        }
        out
    }

    /**
     * Restores every datastore disk of one powered-off VM in place from the session's snapshot. Disks are done one at a
     * time on the recovery host; the live files are kept as <disk>.pre-restore-<ts>. If any disk fails, the disks
     * already restored are put back, so the VM is never left half-restored.
     */
    Map restoreVmInPlace(Session s, String prodMount, Map vm) {
        if (!vm.restorable) {
            throw new IllegalStateException("${vm.name}: not every disk is present in the snapshot")
        }
        if (!(vm.domainState as String)?.contains('shut off')) {
            throw new IllegalStateException("${vm.name} must be powered off (libvirt state: ${vm.domainState})")
        }
        // java.time, not Date.format(): Groovy 4 moved that to groovy-dateutil, which the appliance may not provide
        String ts = STAMP.format(Instant.now())
        List<Map> done = []
        try {
            (vm.disks as List<Map>).each { Map d ->
                HostRunner.Result r = runner.run(s.host, HostScripts.restoreVmDisk(d.snapPath as String, s.mount, prodMount, d.path as String, ts))
                if (!r.ok) {
                    throw new IllegalStateException("${d.target}: ${r.error}")
                }
                done << [target: d.target, path: d.path, kept: r.kv.KEPT, size: r.kv.SIZE]
                step("${vm.name} ${d.target}: restored from ${s.snapshot}; previous disk kept as ${r.kv.KEPT}")
            }
        } catch (Exception e) {
            done.reverseEach { Map d ->
                HostRunner.Result u = runner.run(s.host, HostScripts.undoRestoreVmDisk(d.path as String, d.kept as String))
                step(u.ok ? "rolled back ${d.target}".toString() : "ROLLBACK FAILED for ${d.target}: ${u.error} (previous disk is ${d.kept})".toString())
            }
            throw new IllegalStateException("Restore of ${vm.name} failed and was rolled back: ${e.message}")
        }
        step("${vm.name}: all ${done.size()} disk(s) restored. Start the VM and check it; delete the .pre-restore-${ts} files once satisfied.")
        [vm: vm.name, serverId: vm.serverId, disks: done, timestamp: ts]
    }

    /** Host first (unmount, flush map, delete paths), then the array. Throws if the host is not clean. */
    void closeRecovery(Session s, boolean eradicate = true) {
        HostRunner.Result r = runner.run(s.host, HostScripts.hostCleanup(s.wwid, s.mount))
        if (!r.ok) {
            throw new IllegalStateException("Host ${s.host} not clean (${r.error}); array volume ${s.volume} left in place")
        }
        fa.disconnectVolumeFromHost(s.volume, s.arrayHost)
        fa.destroyVolume(s.volume)
        if (eradicate) {
            try {
                fa.eradicateVolume(s.volume)
            } catch (Exception e) {
                step("eradicate of ${s.volume} refused (${e.message}); it stays in the destroyed bucket")
            }
        }
        step("recovery session ${s.tag} closed")
    }

    private void closeQuietly(Session s, boolean connected) {
        try {
            if (s.serial && connected) {
                closeRecovery(s, true)
            } else if (s.serial) {
                fa.destroyVolume(s.volume)
                fa.eradicateVolume(s.volume)
            }
        } catch (Exception e) {
            step("CLEANUP INCOMPLETE for ${s.volume}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ clone as a new datastore (workflow ii)

    Map cloneAsDatastore(Long clusterId, String hostGroup, List<String> clusterHosts, String sourceVolume, String snapshotName,
                         String newName, String prepHost, String prepArrayHost, Gfs2Registrar registrar) {
        String vol = PureNames.sanitize(newName)
        if (fa.volumeNameTaken(vol)) {
            throw new IllegalStateException("Volume ${vol} already exists on the array")
        }
        String fsName = PureNames.gfs2FsName(newName)
        String serial = null
        boolean privateConn = false
        boolean groupConn = false
        DatastoreOps cluster = new DatastoreOps(fa, runner, clusterHosts, registrar, progress)
        try {
            Map v = fa.copyVolume(snapshotName, vol)
            serial = v.serial
            long bytes = v.provisioned as Long
            String wwid = PureNames.wwid(serial)
            fa.tagVolume(vol, PureNames.tagNamespace, [role: 'gfs2-datastore', source: sourceVolume, snapshot: snapshotName,
                                                        'morpheus-cluster-id': clusterId as String, 'datastore-name': newName])
            step("cloned ${snapshotName} to ${vol}")

            fa.connectVolumeToHost(vol, prepArrayHost)
            privateConn = true
            HostRunner.Result r = runner.run(prepHost, HostScripts.resignature(wwid, fsName))
            if (!r.ok) {
                throw new IllegalStateException("re-signature on ${prepHost} failed: ${r.error}")
            }
            step("re-signatured on ${prepHost}: lock table ${r.kv.OLD_LOCKTABLE} -> ${r.kv.LOCKTABLE}, new UUID, journals replayed")

            HostRunner.Result c = runner.run(prepHost, HostScripts.hostCleanup(wwid))
            if (!c.ok) {
                throw new IllegalStateException("could not release the clone on ${prepHost}: ${c.error}")
            }
            fa.disconnectVolumeFromHost(vol, prepArrayHost)
            privateConn = false
            fa.connectVolumeToHostGroup(vol, hostGroup)
            groupConn = true
            List<String> problems = cluster.verifyVisible(cluster.onAllHosts(HostScripts.rescan(wwid)), bytes)
            if (problems) {
                throw new IllegalStateException("clone not visible on every host: ${problems.join('; ')}")
            }
            Long dsId = registrar.register(clusterId, newName, PureNames.devicePath(serial))
            fa.tagVolume(vol, PureNames.tagNamespace, ['morpheus-datastore-id': dsId as String])
            step("Morpheus adopted ${PureNames.devicePath(serial)} as datastore ${newName} (id ${dsId})")
            return [volume: vol, serial: serial, datastoreId: dsId, lockTable: r.kv.LOCKTABLE]
        } catch (Exception e) {
            step("FAILED: ${e.message}; cleaning up the clone")
            try {
                if (serial) {
                    String wwid = PureNames.wwid(serial)
                    List<String> seen = groupConn ? clusterHosts : (privateConn ? [prepHost] : [])
                    List<String> dirty = seen.collect { String h -> [h, runner.run(h, HostScripts.hostCleanup(wwid))] }
                            .findAll { !(it[1] as HostRunner.Result).ok }.collect { "${it[0]}: ${(it[1] as HostRunner.Result).error}".toString() }
                    if (dirty) {
                        step("CLEANUP STOPPED: hosts not clean (${dirty.join('; ')}); ${vol} left connected")
                    } else {
                        fa.disconnectEverywhere(vol)
                        fa.destroyVolume(vol)
                        fa.eradicateVolume(vol)
                    }
                }
            } catch (Exception ce) {
                step("CLEANUP INCOMPLETE for ${vol}: ${ce.message}")
            }
            throw e
        }
    }
}
