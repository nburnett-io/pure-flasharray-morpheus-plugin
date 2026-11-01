package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.User
import org.purehvm.api.FlashArrayClient
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import groovy.util.logging.Slf4j

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service restore of ONE VM from a FlashArray snapshot of the shared GFS2 datastore it lives on, from the VM's
 * own page. Customers (tier-3 tenants) may only touch VMs of their own tenant; operators of the cluster may touch any.
 *
 * Modes (each opens the restore point read-only through {@link #inSession}):
 *  - overwrite: the VM (powered off) gets all, or the chosen, disks back as they were at the restore point; the
 *    current disks are kept as <disk>.pre-restore-<UTC>, and a failure part-way puts every disk back.
 *  - attachCopy: a copy of one disk is hot-attached to the running VM for file-level recovery (live only, expires).
 *  - addDisk: one disk as it was goes into a NEW empty disk the tab just added through Morpheus Reconfigure.
 *  - cloneInto: a NEW VM, provisioned beforehand through Morpheus with the same layout (the tab does that), gets the
 *    restore point's disks; the original VM is not touched.
 *
 * Nothing a customer sees names the infrastructure: restore points are times behind an opaque id (never the snapshot,
 * protection group, volume, datastore, array, host or cluster), progress lines are written for the customer, and
 * failures that are not {@link #isPublic} are replaced by a generic message. The real detail goes to {@link #detail},
 * the appliance log and the operators' audit trail.
 */
@Slf4j
class VmRestore {
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    /** A clone target created this recently is the tab's own fresh VM: the images it replaces are template copies. */
    static final long FRESH_MS = 24L * RecoverySessions.HOUR_MS

    final MorpheusContext morpheus
    final List<String> progress
    final List<String> detail = Collections.synchronizedList([])
    /** The array snapshot the last call resolved its restore point to (for the operators' audit only). */
    String snapshotUsed
    /** Default lifetime of a copy without a timer (set by the controller from the plugin settings). */
    long copyHours = FileRestoreCopies.DEFAULT_HOURS

    VmRestore(MorpheusContext morpheus, List<String> progress = []) {
        this.morpheus = morpheus
        this.progress = progress
    }

    /** A failure whose message is safe to show a customer (written without infrastructure names). */
    static class PublicError extends IllegalStateException {
        PublicError(String msg) { super(msg) }
    }

    /** Whether a customer may see {@code e}'s own message. Anything else (an ops-layer error naming paths) is not. */
    static boolean isPublic(Throwable e) {
        e instanceof PublicError || e instanceof SecurityException
    }

    /** What a customer is shown for {@code e}: its own message when it is written for them, otherwise a generic one. */
    static String publicMessage(Throwable e, String ref = null) {
        isPublic(e) ? e.message :
                'The restore did not complete; any disk already changed was put back. Your provider has the details' + (ref ? " (reference ${ref})." : '.')
    }

    /** One VM and the storage it sits on. */
    static class Target {
        Instance instance
        ComputeServer server
        Datastore datastore
        PureClusterContext ctx
        Map array            // [storageServerId, volume, wwid, ...] from RetireOps.arrayVolume
        String hostName      // hypervisor the VM is on
        String domain        // libvirt domain (= Morpheus server externalId)
        List<StorageVolume> disks

        /** What the Restore tab shows: the VM and its disks, never where they live. */
        Map toView() {
            [instanceId: instance.id, name: instance.name, power: server.powerState?.toString(),
             disks: disks.collect { [id: it.id, name: it.name, device: device(it), sizeBytes: it.maxStorage, root: it.rootVolume] }]
        }

        StorageVolume disk(Long id) {
            StorageVolume v = id == null ? null : disks.find { it.id == id }
            if (!v) {
                throw new PublicError("Disk ${id} is not a disk of ${instance.name}")
            }
            v
        }
    }

    /** libvirt target (vda, vdb, ...) of a Morpheus disk. */
    static String device(StorageVolume v) {
        (v.deviceDisplayName ?: v.deviceName?.replaceFirst('^/dev/', '')) as String
    }

    /** Opaque, stable id of a restore point for one VM: reveals neither the snapshot nor the volume name. */
    static String pointId(Long instanceId, String snapshot) {
        MessageDigest.getInstance('SHA-256').digest("pure-hvm-restore-point:${instanceId}:${snapshot}".toString().getBytes('UTF-8'))
                .encodeHex().toString().take(24)
    }

    static String when(Object createdMs) {
        createdMs ? WHEN.format(Instant.ofEpochMilli(createdMs as Long)) : 'an unknown time'
    }

    // ------------------------------------------------------------------ resolve

    /**
     * Resolves an instance for a user, refusing anything they may not restore. Only single-VM instances whose disks
     * are all on one FlashArray-backed GFS2 datastore of a linked cluster qualify.
     */
    Target resolve(Long instanceId, User user, Collection<Long> extraOperators) {
        Instance inst = instanceId ? morpheus.services.instance.get(instanceId) : null
        if (!inst) {
            throw new PublicError("No instance ${instanceId}")
        }
        List<Long> serverIds = (inst.containers ?: []).collect { it.server?.id }.findAll().unique()
        ComputeServer s = serverIds.size() == 1 ? morpheus.services.computeServer.get(serverIds[0]) : null
        // authorise before saying anything about the VM: a refusal must not tell another tenant its name or layout
        boolean own = user?.account?.id != null && (inst.account?.id ?: s?.account?.id) == user.account.id
        if (!own && !operatorOf(s, user, extraOperators)) {
            throw new SecurityException('This VM does not belong to your tenant')
        }
        if (!s) {
            throw new PublicError("${inst.name} has ${serverIds.size()} VMs; restore handles single-VM instances")
        }
        List<StorageVolume> disks = (s.volumes ?: []).findAll { it.datastore?.id && !isCopyRecord(it) }
        List<Long> dsIds = disks.collect { it.datastore.id }.unique()
        if (dsIds.size() != 1) {
            throw new PublicError(dsIds ? "${inst.name} keeps its disks in more than one place; restore points cover VMs whose disks are together" :
                    "${inst.name} has no disks restore points cover")
        }
        Datastore ds = morpheus.services.cloud.datastore.get(dsIds[0])
        if (ds?.refType != 'ComputeServerGroup') {
            throw new PublicError("${inst.name} is not on storage with restore points")
        }
        PureClusterContext ctx = new PureClusterContext(morpheus, ds.refId)
        Map arr = new RetireOps(morpheus, ctx).arrayVolume(ds)
        if (arr.error || !arr.volume) {
            log.info("restore: ${inst.name} on ${ds.name}: ${arr.error ?: 'no array volume'}")
            throw new PublicError("${inst.name} is not on storage with restore points")
        }
        String hostName = ctx.hosts.find { it.id == s.parentServer?.id }?.name
        if (!hostName) {
            log.info("restore: ${inst.name} has no host in cluster ${ctx.cluster.name}")
            throw new PublicError("${inst.name} is not running on a host restore can reach right now")
        }
        new Target(instance: inst, server: s, datastore: ds, ctx: ctx, array: arr, hostName: hostName,
                domain: s.externalId ?: s.name, disks: disks)
    }

    /** Whether the user operates the cluster the VM's disks are on (found without revealing anything on failure). */
    private boolean operatorOf(ComputeServer s, User user, Collection<Long> extraOperators) {
        Long dsId = (s?.volumes ?: []).find { it.datastore?.id }?.datastore?.id
        Datastore ds = dsId ? morpheus.services.cloud.datastore.get(dsId) : null
        def cluster = ds?.refType == 'ComputeServerGroup' ? morpheus.services.cluster.get(ds.refId) : null
        cluster != null && Tenancy.isOperator(user, cluster, extraOperators)
    }

    // ------------------------------------------------------------------ the tab's view

    /**
     * Morpheus's sync imports a hot-attached file-restore copy as a volume of the VM (with a bogus path: the datastore
     * directory). Such records are never treated as the VM's disks, and are deleted once the copy is not attached.
     */
    static boolean isCopyRecord(StorageVolume v) {
        "${v.externalId ?: ''} ${v.name ?: ''}".contains(".${HostScripts.FILE_RESTORE_TAG}-")
    }

    /**
     * Deletes the server's volume records of file-restore copies other than {@code attached} (copy file names), or
     * only the record of copy {@code only}.
     */
    static List<String> dropCopyRecords(MorpheusContext morpheus, ComputeServer server, Collection<String> attached, String only = null) {
        List<String> notes = []
        List<StorageVolume> stale = (server?.volumes ?: []).findAll { StorageVolume v ->
            String key = v.externalId ?: v.name ?: ''
            isCopyRecord(v) && (only ? key.endsWith(only) : !attached.any { String a -> key.endsWith(a) })
        }
        if (stale) {
            try {
                MorpheusSession.run(morpheus) { morpheus.async.storageVolume.remove(stale, server, false).blockingGet() }
                notes << "removed Morpheus volume record(s) ${stale*.id} of detached file-restore copies".toString()
            } catch (Exception e) {
                log.warn("removing file-restore volume records ${stale*.id} of ${server.name}: ${e.message}", e)
            }
        }
        notes
    }

    /** A disk's path under its datastore's mount (Morpheus keeps it as externalId; else from volumePath). */
    static String relPath(StorageVolume v) {
        if (v.externalId && !v.externalId.startsWith('/')) {
            return v.externalId
        }
        String mp = v.volumePath ? SnapshotIndex.mountOf(v.volumePath) : null
        mp ? v.volumePath.substring(mp.length() + 1) : null
    }

    /** What the tab needs from the VM's host, in one call: [copies, nics, indexed (index names), has (index name -> the VM's disk paths it holds)]. */
    Map tabInfo(Target t) {
        String dir = vmDir(t)
        List<String> rels = t.disks.collect { relPath(it) }.findAll()
        HostRunner.Result r = t.ctx.runner.run(t.hostName, HostScripts.restoreTabInfo(dir, t.domain, SnapshotIndex.mountOf(dir + '/'), rels))
        if (!r.ok) {
            throw new IllegalStateException("restore tab info: ${r.error}")
        }
        Map<String, Set<String>> has = [:].withDefault { [] as Set<String> }
        r.pairs('HAS').each { List<String> p -> has[p[0]] << p[1] }
        List<Map> nics = r.all('NIC').collect { String l ->
            List<String> p = l.split(/\|/, 3) as List<String>
            [mac: p[0], link: p[1], live: p[2] == '-' ? null : p[2]]
        }
        [copies: readCopies(t, r), nics: nics, indexed: r.all('IDX') as Set<String>, has: has]
    }

    /** "connected", "disconnected" or "partly connected" from tabInfo's NICs (null without NICs). */
    static String networkState(List<Map> nics) {
        if (!nics) {
            return null
        }
        List<Boolean> up = nics.collect { (it.live ?: it.link) == 'up' }
        up.every() ? 'connected' : (up.any() ? 'partly connected' : 'disconnected')
    }

    /** Array snapshots of the VM's datastore, newest first (internal: names included). */
    private List<Map> snapshots(Target t, FlashArrayClient fa) {
        new RecoveryOps(fa, t.ctx.runner).snapshots(t.array.volume as String)
    }

    /**
     * The restore points as a customer may see them: [id, created, kind (scheduled | on demand), coverage, disks].
     *  - indexed snapshot (SnapshotIndex): only points holding at least one of the VM's disks; coverage "full" or
     *    "partial", disks = the ids of the disks it holds
     *  - not indexed yet: points taken before the VM was created are left out; coverage "unverified"
     */
    List<Map> restorePoints(Target t, FlashArrayClient fa, Map info = null) {
        pointsFrom(t, snapshots(t, fa), info)
    }

    static List<Map> pointsFrom(Target t, List<Map> snapshots, Map info) {
        Long born = t.server.dateCreated?.time
        snapshots.findResults { Map s ->
            String idx = SnapshotIndex.fileName(s.name as String)
            Map p = [id: pointId(t.instance.id, s.name as String), created: s.created, kind: s.protectionGroup ? 'scheduled' : 'on demand']
            if (info?.indexed?.contains(idx)) {
                Set<String> in_ = info.has[idx] as Set<String>
                List<Long> present = t.disks.findAll { in_.contains(relPath(it)) }*.id
                if (!present) {
                    return null
                }
                return p + [coverage: present.size() == t.disks.size() ? 'full' : 'partial', disks: present]
            }
            if (born && s.created && (s.created as Long) < born) {
                return null
            }
            p + [coverage: 'unverified', disks: t.disks*.id]
        }
    }

    /** Connects or disconnects all of the VM's network interfaces (persistent definition, and live if running). */
    Map setNetwork(Target t, boolean connect) {
        HostRunner.Result r = t.ctx.runner.run(t.hostName, HostScripts.setLinks(t.domain, connect))
        if (!r.ok) {
            throw new IllegalStateException("set links ${connect}: ${r.error}")
        }
        say("${t.instance.name}: network ${connect ? 'connected' : 'disconnected'}")
        [network: connect ? 'connected' : 'disconnected', interfaces: r.all('SET').size()]
    }

    // ------------------------------------------------------------------ shared by every restore mode

    private void say(String s) {
        progress << s
    }

    /** The snapshot behind an opaque restore-point id. */
    private Map point(Target t, FlashArrayClient fa, String id) {
        Map s = id ? snapshots(t, fa).find { pointId(t.instance.id, it.name as String) == id } : null
        if (!s) {
            throw new PublicError('That restore point no longer exists; reload the list')
        }
        snapshotUsed = s.name
        detail << "restore point ${id} = snapshot ${s.name}".toString()
        s
    }

    /**
     * The VM must be off (or on): per Morpheus, and per libvirt when {@code vm} (from the session) is given - Morpheus's
     * power state lags a guest that was just started or stopped.
     */
    private static void requirePower(Target t, boolean on, Map vm = null) {
        String morpheusState = (t.server.powerState?.toString() ?: '').toLowerCase()
        String libvirtState = vm?.domainState as String
        boolean ok = morpheusState == (on ? 'on' : 'off') && (libvirtState == null || libvirtState.contains(on ? 'running' : 'shut off'))
        if (!ok) {
            throw new PublicError(on ? "${t.instance.name} must be running to attach a copy for file recovery" : "${t.instance.name} must be stopped first")
        }
    }

    /**
     * Opens the restore point read-only on the VM's host, records what it holds (SnapshotIndex), reads the VM's disks
     * in it, runs {@code work(ops, session, prodMount, vm)}, and always closes the restore point afterwards.
     */
    private <T> T inSession(Target t, FlashArrayClient fa, Map snap, Closure<T> work) {
        say("opening the restore point from ${when(snap.created)} (read-only)")
        RecoveryOps ops = new RecoveryOps(fa, t.ctx.runner, detail)
        long expires = System.currentTimeMillis() + 2L * RecoverySessions.HOUR_MS   // sweeper backstop if we die
        RecoveryOps.Session s = ops.openRecovery(t.array.volume as String, snap.name as String, t.hostName,
                t.ctx.arrayHostFor(fa, t.hostName), t.ctx.cluster.id, expires)
        try {
            String pm = RecoveryOps.mountOf(t.ctx.runner, s.host, t.array.wwid as String)
            SnapshotIndex.save(t.ctx.runner, s, pm)
            Map vm = ops.vmsInSession(s, pm, [[serverId: t.server.id, name: t.instance.name, domain: t.domain, host: t.hostName]]).find()
            if (!vm) {
                throw new IllegalStateException("${t.instance.name}: libvirt reports no disks on ${t.datastore.name}")
            }
            work.call(ops, s, pm, vm)
        } finally {
            try {
                ops.closeRecovery(s)
                say('restore point closed')
            } catch (Exception e) {
                log.warn("closing recovery session ${s.tag} failed: ${e.message}", e)
                detail << "closing recovery session ${s.tag} failed: ${e.message}".toString()
                say('the restore point could not be closed right away; it closes automatically')
            }
        }
    }

    /** The libvirt disk behind a Morpheus disk, checked against the path Morpheus records for it. */
    private static Map libvirtDisk(Map vm, StorageVolume v, Target t) {
        Map d = (vm.disks as List<Map>).find { it.target == device(v) }
        if (!d || (v.volumePath && v.volumePath != d.path)) {
            throw new IllegalStateException("${t.instance.name} disk ${v.name}: libvirt ${d?.target}=${d?.path}, Morpheus ${device(v)}=${v.volumePath}")
        }
        d
    }

    /** {@link #libvirtDisk}, refused unless the disk existed at the restore point. */
    private static Map snapDisk(Map vm, StorageVolume v, Target t, Map snap, String hint = '') {
        Map d = libvirtDisk(vm, v, t)
        if (!d.inSnapshot) {
            throw new PublicError("Disk ${v.name} of ${t.instance.name} did not exist yet at ${when(snap.created)}${hint}")
        }
        d
    }

    private void grow(HostRunner r, String host, String path, Long bytes) {
        if (bytes) {
            HostRunner.Result g = r.run(host, HostScripts.growTo(path, bytes))
            if (!g.ok) {
                detail << "growing ${path} to ${bytes}: ${g.error}".toString()
            }
        }
    }

    // ------------------------------------------------------------------ overwrite

    /**
     * Overwrite: put the restore point's disks back into the (powered-off) VM itself - every disk, or only
     * {@code volumeIds} (Morpheus disk ids of this VM).
     */
    Map overwrite(Target t, FlashArrayClient fa, String pointId, List<Long> volumeIds = null) {
        List<StorageVolume> chosen = volumeIds ? volumeIds.unique().collect { t.disk(it) } : t.disks
        Map snap = point(t, fa, pointId)
        requirePower(t, false)
        inSession(t, fa, snap) { RecoveryOps ops, RecoveryOps.Session s, String pm, Map vm ->
            requirePower(t, false, vm)
            List<Map> disks = chosen.collect { snapDisk(vm, it, t, snap, volumeIds ? '' : '; overwrite chosen disks instead') }
            String which = volumeIds ? 'disk ' + chosen*.name.join(', ') : "all ${disks.size()} disk(s)"
            say("restoring ${which} of ${t.instance.name}")
            Map r = ops.restoreVmInPlace(s, pm, vm + [disks: disks, restorable: true])
            say("${t.instance.name}: ${which} back to ${when(snap.created)}. The replaced disks are kept until your provider removes them.")
            [instanceId: t.instance.id, restoredTo: snap.created, disks: (r.disks as List).size()]
        }
    }

    // ------------------------------------------------------------------ file recovery copies

    /**
     * File-level restore: a copy of disk {@code sourceVolumeId} as it was at the restore point, hot-attached to the
     * RUNNING VM as an extra disk the customer copies files from in the guest. Live only (never in the VM's definition
     * or Morpheus's record): it cannot be booted from, and it leaves the VM when the VM powers off. The copy file stays
     * in the VM's directory until removed ({@link #removeCopy}) or reaped by FileRestoreCopies after {@code hours}.
     */
    Map attachCopy(Target t, FlashArrayClient fa, String pointId, Long sourceVolumeId, long hours = FileRestoreCopies.DEFAULT_HOURS) {
        StorageVolume src = t.disk(sourceVolumeId)
        Map snap = point(t, fa, pointId)
        requirePower(t, true)
        inSession(t, fa, snap) { RecoveryOps ops, RecoveryOps.Session s, String pm, Map vm ->
            requirePower(t, true, vm)
            Map from = snapDisk(vm, src, t, snap)
            // named after the restore point's time, so the list can say what each copy is
            String dest = "${dirOf(from.path as String)}/${t.domain}.${HostScripts.FILE_RESTORE_TAG}-${RecoveryOps.STAMP.format(Instant.ofEpochMilli((snap.created ?: 0L) as Long))}-${from.target}.qcow2".toString()
            if (copies(t).any { it.copy == baseName(dest) }) {
                throw new PublicError("Disk ${src.name} as of ${when(snap.created)} is already attached; use that copy or remove it first")
            }
            say("copying disk ${src.name} as of ${when(snap.created)}")
            HostRunner.Result r = t.ctx.runner.run(s.host, HostScripts.fileRestoreAttach(from.snapPath as String, s.mount, dest, t.domain))
            if (!r.ok) {
                throw new IllegalStateException("file-restore attach: ${r.error}")
            }
            long expires = System.currentTimeMillis() + hours * RecoverySessions.HOUR_MS
            HostRunner.Result tm = t.ctx.runner.run(s.host, HostScripts.fileRestoreTimer(pm, baseName(dest), expires))
            if (!tm.ok) {   // the sweeper then counts from the file's time with the default lifetime
                detail << "copy timer for ${baseName(dest)}: ${tm.error}".toString()
            }
            say("attached to ${t.instance.name} as ${r.kv.TARGET}: disk ${src.name} as of ${when(snap.created)}. " +
                    "Bring it online in the guest and copy what you need. It is detached and deleted automatically at ${when(expires)} " +
                    'unless you keep it longer; remove it here when you are done.')
            [instanceId: t.instance.id, restoredTo: snap.created, attachedAs: r.kv.TARGET, copy: baseName(dest), expires: expires]
        }
    }

    private static String dirOf(String path) { path.substring(0, path.lastIndexOf('/')) }

    private static String baseName(String path) { path.substring(path.lastIndexOf('/') + 1) }

    /** The VM's directory on its datastore (where its disks and its file-restore copies live). */
    private String vmDir(Target t) {
        String p = t.disks.find { it.rootVolume && it.volumePath }?.volumePath ?: t.disks.find { it.volumePath }?.volumePath
        if (!p) {
            p = t.ctx.runner.run(t.hostName, HostScripts.vmDisks(t.domain)).pairs('DISK').collect { it[1] }.find { it?.startsWith('/') }
        }
        if (!p) {
            throw new IllegalStateException("${t.instance.name}: no disk path known")
        }
        dirOf(p)
    }

    /**
     * The VM's file-restore copies: [copy (file name), restoredTo, fromDisk, sizeBytes, attachedAs (null = not
     * attached), expires (when the sweeper removes it)].
     */
    List<Map> copies(Target t) {
        String dir = vmDir(t)
        HostRunner.Result r = t.ctx.runner.run(t.hostName, HostScripts.fileRestoreList(dir, t.domain, SnapshotIndex.mountOf(dir + '/')))
        if (!r.ok) {
            throw new IllegalStateException("file-restore list: ${r.error}")
        }
        readCopies(t, r)
    }

    /** COPY= lines as copies; drops Morpheus's records of copies that are no longer attached. */
    private List<Map> readCopies(Target t, HostRunner.Result r) {
        List<Map> rows = r.all('COPY').collect { String line ->
            List<String> p = line.split(/\|/, 5) as List<String>
            String name = baseName(p[0])
            java.util.regex.Matcher mm = name =~ /\.${HostScripts.FILE_RESTORE_TAG}-([0-9]{14})-([a-z]+)\.qcow2$/
            boolean hit = mm.find()
            String dev = hit ? mm.group(2) : null
            [copy: name, restoredTo: hit ? Instant.from(RecoveryOps.STAMP.parse(mm.group(1))).toEpochMilli() : null,
             fromDisk: t.disks.find { device(it) == dev }?.name ?: dev, sizeBytes: p[1] as Long, attachedAs: p[2] == '-' ? null : p[2],
             expires: FileRestoreCopies.expiresOf(p.size() > 3 ? p[3] : null, p.size() > 4 ? p[4] : null, copyHours)]
        }
        detail.addAll(dropCopyRecords(morpheus, t.server, rows.findAll { it.attachedAs }*.copy as List<String>))
        rows
    }

    private void requireCopy(Target t, String copy) {
        if (!(copy ==~ /[A-Za-z0-9._:-]+/) || !copies(t).any { it.copy == copy }) {
            throw new PublicError('That copy no longer exists; reload the list')
        }
    }

    /** Keeps a copy until {@code hours} from now (the caller caps hours at the maximum). */
    Map extendCopy(Target t, String copy, long hours) {
        requireCopy(t, copy)
        long expires = System.currentTimeMillis() + hours * RecoverySessions.HOUR_MS
        HostRunner.Result r = t.ctx.runner.run(t.hostName, HostScripts.fileRestoreTimer(SnapshotIndex.mountOf(vmDir(t) + '/'), copy, expires))
        if (!r.ok) {
            throw new IllegalStateException("copy timer: ${r.error}")
        }
        say("the copy is kept until ${when(expires)}")
        [copy: copy, expires: expires]
    }

    /** Detaches (if attached) and deletes one of the VM's file-restore copies, and Morpheus's record of it. */
    Map removeCopy(Target t, String copy) {
        requireCopy(t, copy)
        String dir = vmDir(t)
        HostRunner.Result r = t.ctx.runner.run(t.hostName, HostScripts.fileRestoreRemove("${dir}/${copy}".toString(), t.domain, SnapshotIndex.mountOf(dir + '/')))
        if (!r.ok) {
            if ((r.error ?: '').contains('did not release')) {
                throw new PublicError('The guest is still using the disk; unmount it (or set it offline) in the guest and try again')
            }
            throw new IllegalStateException("file-restore remove: ${r.error}")
        }
        say("removed the copy${r.kv.DETACHED ? " (detached from ${r.kv.DETACHED})" : ''}")
        detail.addAll(dropCopyRecords(morpheus, t.server, [], copy))
        [removed: copy]
    }

    // ------------------------------------------------------------------ add as a new disk

    /**
     * Add as a new disk: disk {@code sourceVolumeId} as it was at the restore point goes into {@code newVolumeId}, a
     * disk added to the same (powered-off) VM after the restore point. Only a disk that did not exist at the restore
     * point and holds no data is accepted as the target, so this never overwrites anything the VM has.
     */
    Map addDisk(Target t, FlashArrayClient fa, String pointId, Long sourceVolumeId, Long newVolumeId) {
        StorageVolume src = t.disk(sourceVolumeId), dst = t.disk(newVolumeId)
        if (src.id == dst.id) {
            throw new PublicError('Pick a new disk to restore into, not the disk itself')
        }
        Map snap = point(t, fa, pointId)
        requirePower(t, false)
        HostRunner r = t.ctx.runner
        inSession(t, fa, snap) { RecoveryOps ops, RecoveryOps.Session s, String pm, Map vm ->
            requirePower(t, false, vm)
            Map from = snapDisk(vm, src, t, snap), into = libvirtDisk(vm, dst, t)
            if (into.inSnapshot) {
                throw new PublicError("Disk ${dst.name} already existed at ${when(snap.created)}; restore into a newly added disk")
            }
            say("copying disk ${src.name} as of ${when(snap.created)} into new disk ${dst.name}")
            HostRunner.Result res = r.run(s.host, HostScripts.restoreVmDisk(from.snapPath as String, s.mount, pm, into.path as String,
                    RecoveryOps.STAMP.format(Instant.now())))
            if (!res.ok) {
                throw new IllegalStateException("${into.target}: ${res.error}")
            }
            HostRunner.Result g = r.run(s.host, HostScripts.removeKeptIfBlank(into.path as String, res.kv.KEPT as String))
            if (!g.ok || g.kv.KEPT_DATA) {
                // not empty after all: put back what was there
                r.run(s.host, HostScripts.undoRestoreVmDisk(into.path as String, res.kv.KEPT as String))
                throw new PublicError("Disk ${dst.name} is not empty; restore into a newly added, empty disk")
            }
            grow(r, s.host, into.path as String, dst.maxStorage)
            say("disk ${dst.name} of ${t.instance.name} now holds disk ${src.name} as of ${when(snap.created)}. " +
                    'Start the VM and bring the disk online to copy files from it; remove the disk when you are done.')
            [instanceId: t.instance.id, restoredTo: snap.created, disk: dst.id]
        }
    }

    // ------------------------------------------------------------------ clone to a new VM

    /**
     * Clone: the restore point's disks go into {@code dst}, a VM the user just provisioned with the same disk layout
     * and powered off. Disks are matched by device (vda -> vda), all checked before any is copied; the copy is
     * RecoveryOps.restoreVmInPlace into dst's files, so a failure part-way puts dst's disks back. The source VM is never
     * touched.
     *
     * Unless {@code connect}, the clone's network links are set down BEFORE it gets the disks: it carries the
     * original's identity (hostname, machine/domain SID, static IPs, services) and must not meet the original on the
     * network until someone decides to connect it.
     */
    Map cloneInto(Target src, Target dst, FlashArrayClient fa, String pointId, boolean connect = false) {
        if (src.ctx.cluster.id != dst.ctx.cluster.id) {
            throw new PublicError('The new VM must be created in the same place as the original; create it from this tab')
        }
        if (src.instance.id == dst.instance.id) {
            throw new PublicError('Pick a new VM, not the original')
        }
        Map snap = point(src, fa, pointId)
        requirePower(dst, false)
        if (!connect) {
            try {
                setNetwork(dst, false)
            } catch (Exception e) {
                detail << e.message
                throw new PublicError("${dst.instance.name}'s network could not be disconnected, so it was not given the disks. Your provider has the details.")
            }
        }
        HostRunner r = src.ctx.runner
        Map done = inSession(src, fa, snap) { RecoveryOps ops, RecoveryOps.Session s, String pm, Map vm ->
            if (!(vm.disks as List<Map>).every { it.inSnapshot }) {
                throw new PublicError("${src.instance.name} did not exist yet, or had different disks, at ${when(snap.created)}")
            }
            HostRunner.Result td = r.run(dst.hostName, HostScripts.vmDisks(dst.domain))
            requirePower(dst, false, [domainState: td.kv.STATE ?: '?'])
            Map<String, String> dstDisks = td.pairs('DISK').collectEntries { [(it[0]): it[1]] }
            String dstPm = RecoveryOps.mountOf(r, s.host, dst.array.wwid as String)
            List<Map> disks = (vm.disks as List<Map>).collect { Map d ->
                String target = dstDisks[d.target as String]
                if (!target?.startsWith(dstPm + '/')) {
                    throw new PublicError("${dst.instance.name} does not have the same disks as ${src.instance.name}")
                }
                d + [path: target]
            }
            Map res = ops.restoreVmInPlace(s, dstPm, [name: dst.instance.name, domainState: td.kv.STATE, restorable: true, disks: disks])
            say("${dst.instance.name}: ${disks.size()} disk(s) copied from ${src.instance.name} as of ${when(snap.created)}")
            res
        }
        List<Map> copied = done.disks as List<Map>
        copied.each { Map d -> grow(r, src.hostName, d.path as String, dst.disks.find { device(it) == d.target }?.maxStorage) }
        // The images replaced in a VM the tab just created are template copies, not anyone's data. A target that has
        // been around longer (the route accepts any stopped VM of the tenant) keeps them.
        Date created = dst.server.dateCreated
        if (created && System.currentTimeMillis() - created.time < FRESH_MS) {
            copied.each { Map d -> r.run(src.hostName, HostScripts.removeKept(d.path as String, d.kept as String)) }
        } else {
            say("${dst.instance.name}'s previous disks are kept until your provider removes them")
        }
        say("${dst.instance.name} now holds ${src.instance.name}'s disks as of ${when(snap.created)}; ${src.instance.name} was not touched")
        [sourceInstanceId: src.instance.id, instanceId: dst.instance.id, restoredTo: snap.created, disks: copied.size(),
         network: connect ? 'connected' : 'disconnected']
    }
}
