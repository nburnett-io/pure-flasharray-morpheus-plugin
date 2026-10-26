package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Permission
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import org.purehvm.PureFlashArrayPlugin
import org.purehvm.api.CertProbe
import org.purehvm.api.FlashArrayClient
import org.purehvm.api.JdkHttpTransport
import org.purehvm.api.PureNames
import org.purehvm.compliance.HostFacts
import org.purehvm.compliance.Remediation
import org.purehvm.ops.Adoption
import org.purehvm.ops.DatastoreOps
import org.purehvm.ops.Gfs2Registrar
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import org.purehvm.planning.HostGroupApplier
import org.purehvm.planning.HostGroupPlanner
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * JSON endpoints behind the cluster tab (operators) and the instance Restore tab (customers and operators,
 * pure-flasharray-restore), served under /plugin/pure-hvm/*. On the cluster tab, pure-flasharray-ops "read" views and
 * "full" runs snapshot recovery; changing the storage itself (arrays, host groups, hosts, datastores, deleting files)
 * needs pure-flasharray-admin "full" as well. Long changes run as background jobs (poll /job, or /vm-job for the
 * Restore tab); quick ones run inline.
 *
 * A cluster can be linked to several FlashArrays. Every array-scoped request names one with storageServerId; it may
 * be left out only while the cluster has a single link. Recovery requests use the array their session was opened on.
 */
@Slf4j
class PureController implements PluginController {
    static final String PERM = 'pure-flasharray-ops'
    static final String PERM_RESTORE = 'pure-flasharray-restore'
    static final String PERM_ADMIN = 'pure-flasharray-admin'
    static final List<String> VM_JOB_KINDS = ['vm-restore', 'vm-clone', 'vm-add-disk', 'vm-file-restore']
    static final long GIB = 1L << 30

    final Plugin plugin
    final MorpheusContext morpheus
    /** Recovery sessions live on the array (clone tags); this is RecoverySessions' cache, rebuilt after a reload. */
    private static Map<String, Map> getSessions() { RecoverySessions.CACHE }

    private long recoveryTimeoutHours() {
        RecoverySessions.timeoutHours(settings())
    }

    PureController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { 'pure-flasharray-hvm.controller' }
    @Override String getName() { 'Pure FlashArray HVM controller' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    @Override
    List<Route> getRoutes() {
        Permission read = Permission.build(PERM, 'read')
        Permission full = Permission.build(PERM, 'full')
        Permission admin = Permission.build(PERM_ADMIN, 'full')
        Permission restoreRead = Permission.build(PERM_RESTORE, 'read')
        Permission restoreFull = Permission.build(PERM_RESTORE, 'full')
        [
                // self-service VM restore (instance page): customers and operators
                Route.build('/pure-hvm/vm-restore-points', 'vmRestorePoints', restoreRead),
                Route.build('/pure-hvm/vm-restore', 'vmRestore', restoreFull),
                Route.build('/pure-hvm/vm-clone', 'vmClone', restoreFull),
                Route.build('/pure-hvm/vm-add-disk', 'vmAddDisk', restoreFull),
                Route.build('/pure-hvm/vm-file-restore', 'vmFileRestore', restoreFull),
                Route.build('/pure-hvm/vm-file-restore-remove', 'vmFileRestoreRemove', restoreFull),
                Route.build('/pure-hvm/vm-file-restore-extend', 'vmFileRestoreExtend', restoreFull),
                Route.build('/pure-hvm/vm-network', 'vmNetwork', restoreFull),
                Route.build('/pure-hvm/vm-job', 'vmJob', restoreRead),
                // cluster tab, viewing (Operations: read)
                Route.build('/pure-hvm/status', 'status', read),
                Route.build('/pure-hvm/arrays', 'arrays', read),
                Route.build('/pure-hvm/snapshots', 'snapshots', read),
                Route.build('/pure-hvm/snapshot-policies', 'snapshotPolicies', read),
                Route.build('/pure-hvm/job', 'job', read),
                Route.build('/pure-hvm/compliance', 'compliance', read),
                Route.build('/pure-hvm/recovery-vms', 'recoveryVms', read),
                Route.build('/pure-hvm/hosts', 'hosts', read),
                Route.build('/pure-hvm/array-hosts', 'arrayHosts', read),
                Route.build('/pure-hvm/retire-plan', 'retirePlan', read),
                Route.build('/pure-hvm/datastore-tenants', 'datastoreTenants', read),
                Route.build('/pure-hvm/reclaim-scan', 'reclaimScan', read),
                // snapshot recovery (Operations: full)
                Route.build('/pure-hvm/snapshot-create', 'snapshotCreate', full),
                Route.build('/pure-hvm/recovery-open', 'recoveryOpen', full),
                Route.build('/pure-hvm/recovery-restore', 'recoveryRestore', full),
                Route.build('/pure-hvm/recovery-close', 'recoveryClose', full),
                Route.build('/pure-hvm/recovery-restore-vm', 'recoveryRestoreVm', full),
                Route.build('/pure-hvm/recovery-extend', 'recoveryExtend', full),
                Route.build('/pure-hvm/datastore-refresh', 'datastoreRefresh', full),
                // changing the storage (Administration: full)
                Route.build('/pure-hvm/link', 'link', admin),
                Route.build('/pure-hvm/unlink', 'unlink', admin),
                Route.build('/pure-hvm/hostgroup-apply', 'hostGroupApply', admin),
                Route.build('/pure-hvm/remediate', 'remediate', admin),
                Route.build('/pure-hvm/datastore-create', 'datastoreCreate', admin),
                Route.build('/pure-hvm/datastore-grow', 'datastoreGrow', admin),
                Route.build('/pure-hvm/clone-datastore', 'cloneDatastore', admin),
                Route.build('/pure-hvm/host-add', 'hostAdd', admin),
                Route.build('/pure-hvm/host-mount', 'hostMount', admin),
                Route.build('/pure-hvm/host-rescan', 'hostRescan', admin),
                Route.build('/pure-hvm/adopt', 'adopt', admin),
                Route.build('/pure-hvm/array-host-remove', 'arrayHostRemove', admin),
                Route.build('/pure-hvm/iqn-sync', 'iqnSync', admin),
                Route.build('/pure-hvm/retire-finish', 'retireFinish', admin),
                Route.build('/pure-hvm/retire-morpheus', 'retireMorpheus', admin),
                Route.build('/pure-hvm/datastore-share', 'datastoreShare', admin),
                Route.build('/pure-hvm/reclaim-delete', 'reclaimDelete', admin),
        ]
    }

    /** Route methods that change the storage itself: Administration "full" (see getRoutes). */
    static Set<String> adminMethods(List<Route> routes) {
        routes.findAll { r -> r.permissions.any { it.code == PERM_ADMIN } }*.method as Set<String>
    }

    /**
     * What the cluster tab shows this user: false hides the storage-changing buttons (Morpheus refuses the routes
     * anyway). Null when Morpheus did not pass the user's permissions: the buttons stay and the routes decide.
     */
    static Boolean canAdmin(User u) {
        u?.permissions == null ? null : u.permissions[PERM_ADMIN] == 'full'
    }

    // ------------------------------------------------------------------ helpers

    private static JsonResponse ok(Object data) {
        JsonResponse.of([success: true, data: data])
    }

    private static JsonResponse fail(String msg, int status = 400) {
        JsonResponse r = JsonResponse.of([success: false, msg: msg])
        r.status = status
        r
    }

    private JsonResponse guarded(Closure<JsonResponse> c) {
        try {
            c.call()
        } catch (SecurityException e) {
            fail(e.message, 403)
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail(e.message)
        } catch (Exception e) {
            log.error('Pure plugin request failed', e)
            fail("${e.class.simpleName}: ${e.message}", 500)
        }
    }

    private static Map p(ViewModel<Map> m) {
        (m.object ?: [:]) as Map
    }

    private static Long clusterId(ViewModel<Map> m) {
        requiredLong(m, 'clusterId')
    }

    private static Long optionalLong(Object v) {
        v?.toString()?.isLong() ? v as Long : null
    }

    private static Long requiredLong(ViewModel<Map> m, String name) {
        Long v = optionalLong(p(m)[name])
        if (!v) {
            throw new IllegalArgumentException("${name} is required")
        }
        v
    }

    /** A checkbox-style flag from a request or the settings: true, 'true' or 'on'. */
    private static boolean flag(Object v) {
        v in [true, 'true', 'on']
    }

    private static long bytesOf(ViewModel<Map> m) {
        ((p(m).sizeGiB ?: 0) as BigDecimal * GIB).longValue()
    }

    private static void requireHostGroup(Map link) {
        if (!link.hostGroup) {
            throw new IllegalStateException('Configure the host group on this array first')
        }
    }

    private static String volumeWwid(FlashArrayClient fa, String volume) {
        PureNames.wwid(fa.getVolume(volume).serial as String)
    }

    private static String user(ViewModel<Map> m) {
        m.user?.username ?: 'unknown'
    }

    private Map settings() {
        PureFlashArrayPlugin.readSettings(morpheus, plugin)
    }

    private boolean experimental() {
        flag(settings().enableExperimental)
    }

    private Gfs2Registrar registrar() {
        Map s = settings()
        // in-process unless REST is chosen explicitly: a master-tenant token cannot add datastores to a subtenant's cluster
        if (s.registrationMode != 'rest') {
            return new InProcessGfs2Registrar(morpheus)
        }
        String url = s.applianceUrl as String
        String fp = (s.applianceFingerprint as String) ?: applianceTofu(url)
        new RestGfs2Registrar(url, s.applianceToken as String, new JdkHttpTransport(fp))
    }

    /** Appliance certificate pinned on first use (per plugin load) when no fingerprint is configured. */
    private static final Map<String, String> APPLIANCE_PINS = new ConcurrentHashMap<>()

    private static String applianceTofu(String url) {
        if (!url?.startsWith('https://')) {
            return null   // RestGfs2Registrar rejects it with a clear message
        }
        APPLIANCE_PINS.computeIfAbsent(url) {
            Map cert = CertProbe.probe(url)
            log.info("Morpheus appliance ${url}: pinned certificate ${cert.pretty} on first use; set it in the plugin settings to make it permanent")
            cert.pretty as String
        }
    }

    /** The link (array + host group) a request is about: storageServerId, or the cluster's only link. */
    private static Map linkFor(PureClusterContext ctx, ViewModel<Map> m) {
        ctx.resolve(p(m).storageServerId)
    }


    /**
     * The cluster a request is about, for OPERATORS only: the master tenant, the tenant owning the cluster, or one in
     * the "Operator tenants" setting. Customer tenants are refused here whatever their role grants (see Tenancy).
     */
    private PureClusterContext operatorCtx(ViewModel<Map> m) {
        PureClusterContext ctx = new PureClusterContext(morpheus, clusterId(m))
        if (!Tenancy.isOperator(m.user, ctx.cluster, Tenancy.operatorTenantIds(settings()))) {
            Audit.warn(morpheus, m.user, ctx.cluster, 'refused: customer tenant called an operator route', [tenant: m.user?.account?.name])
            throw new SecurityException("Storage operations on cluster ${ctx.cluster.name} are reserved for the tenant that runs it. " +
                    'Restore your own VMs from the instance page.')
        }
        ctx
    }

    /** Runs a job and puts its start and its outcome in Morpheus's audit log, with the request's parameters. */
    private JsonResponse auditedJob(ViewModel<Map> m, PureClusterContext ctx, String kind, Closure<Object> work) {
        User who = m.user
        Map params = p(m)   // Audit drops _* and routing keys
        Audit.info(morpheus, who, ctx.cluster, "${kind} started", params)
        ok(Jobs.start(kind, ctx.cluster.id, user(m)) { List<String> progress ->
            try {
                Object r = work.call(progress)
                Audit.info(morpheus, who, ctx.cluster, "${kind} done", params)
                r
            } catch (Throwable t) {
                Audit.warn(morpheus, who, ctx.cluster, "${kind} failed: ${t.message}", params)
                throw t
            }
        }.toMap())
    }

    private JsonResponse startJob(ViewModel<Map> m, String kind, Closure work) {
        PureClusterContext ctx = operatorCtx(m)
        auditedJob(m, ctx, kind) { List<String> progress -> work.call(ctx, progress) }
    }

    /** A job on one linked array: the link is resolved before the job starts, so a wrong array fails the request. */
    private JsonResponse startArrayJob(ViewModel<Map> m, String kind, Closure work) {
        PureClusterContext ctx = operatorCtx(m)
        Map link = linkFor(ctx, m)
        auditedJob(m, ctx, kind) { List<String> progress ->
            ctx.withArray(link.storageServerId as Long) { FlashArrayClient fa -> work.call(ctx, link, fa, progress) }
        }
    }

    /** Audit entry for an inline (non-job) change. */
    private void audit(ViewModel<Map> m, PureClusterContext ctx, String action, Map details = [:]) {
        Audit.info(morpheus, m.user, ctx.cluster, action, details)
    }

    /** Adoption scan of every linked array, merged per datastore (each row carries its storageServerId). */
    private List<Map> adoptionRows(PureClusterContext ctx) {
        List<Adoption.DatastoreRef> ds = HostOnboarding.clusterDatastores(ctx)
        Map<Long, List<Map>> bySid = [:]
        ctx.links.each { Map l ->
            bySid[l.storageServerId as Long] = ctx.withArray(l.storageServerId as Long) { FlashArrayClient fa ->
                Adoption.scan(fa, ctx.cluster.id, l.hostGroup as String, ds)
            }
        }
        Adoption.merge(bySid)
    }

    // ------------------------------------------------------------------ read

    def arrays(ViewModel<Map> m) {
        guarded {
            operatorCtx(m)
            List<StorageServer> servers = morpheus.services.storageServer.list(new DataQuery().withFilter('type.code', PureStorageProvider.CODE))
            ok(servers.collect { [id: it.id, name: it.name, url: it.serviceUrl, status: it.status, version: it.serviceVersion] })
        }
    }

    /** The cluster with one entry per linked array; an unreachable array reports its error without hiding the others. */
    def status(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            List<Adoption.DatastoreRef> ds = HostOnboarding.clusterDatastores(ctx)
            long hours = recoveryTimeoutHours()
            // Morpheus side of each cluster datastore: a tagged LUN whose record is gone is a half-finished Retire
            Map<Long, Map> dsRecords = ds.collectEntries { [(it.id): [active: it.active]] }
            Map<Long, List<Map>> scans = [:]
            List<Map> links = ctx.links.collect { Map l ->
                Long sid = l.storageServerId as Long
                Map out = [storageServerId: sid, hostGroup: l.hostGroup]
                try {
                    out.server = ctx.storageServer(sid).name
                    ctx.withArray(sid) { FlashArrayClient fa ->
                        Map a = fa.array
                        out.array = [name: a.name, version: a.version, api: fa.apiVersion]
                        out.portals = fa.listIscsiPortals()
                        Map<String, Map<String, String>> byVol = fa.tagsByVolume()
                        List<String> mine = byVol.findAll { k, v -> PureNames.ownedBy(v, ctx.cluster.id) }*.key
                        // tags outlive a destroyed (not yet eradicated) volume, and naming a destroyed volume is an
                        // error on the array: list the live volumes and filter (a retired datastore broke this view)
                        out.volumes = mine ? fa.listVolumes([destroyed: false]).findAll { mine.contains(it.name) }.collect { Map v ->
                            Map<String, String> vt = byVol[v.name as String]
                            Long dsId = vt['morpheus-datastore-id']?.isLong() ? vt['morpheus-datastore-id'] as Long : null
                            Map rec = dsRecords[dsId]
                            [name: v.name, serial: v.serial, provisioned: v.provisioned, used: (v.space as Map)?.total_physical,
                             devicePath: PureNames.devicePath(v.serial as String), tags: vt, storageServerId: sid,
                             datastoreId: dsId, morpheusMissing: dsId != null && rec == null, morpheusActive: rec?.active]
                        } : []
                        scans[sid] = Adoption.scan(fa, ctx.cluster.id, l.hostGroup as String, ds)
                    }
                } catch (Exception e) {
                    log.warn("status of array ${sid} for cluster ${ctx.cluster.name} failed: ${e.message}")
                    out.error = e.message
                }
                out
            }
            ok([cluster: [id: ctx.cluster.id, name: ctx.cluster.name], hosts: ctx.hostNames, linked: !links.isEmpty(), links: links,
                experimental: experimental(), canAdmin: canAdmin(m.user), jobs: Jobs.forCluster(ctx.cluster.id).take(10)*.toMap(),
                compliance: (ComplianceMonitor.LAST[ctx.cluster.id] ?: [:]) + [everyHours: ComplianceMonitor.intervalHours(settings())],
                existing: scans ? Adoption.merge(scans).findAll { it.state != 'adopted' } : [],
                recoveries: RecoverySessions.refresh(ctx, hours).collect { sessionView(it) }, recoveryTimeoutHours: hours])
        }
    }

    def snapshots(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            String vol = p(m).volume as String
            ok(ctx.withArray(linkFor(ctx, m).storageServerId as Long) { FlashArrayClient fa -> new RecoveryOps(fa, ctx.runner).snapshots(vol) })
        }
    }

    /** Read-only: the array's protection groups over this cluster's datastores, and SafeMode, per linked array. */
    def snapshotPolicies(ViewModel<Map> m) {
        guarded {
            ok(SnapshotPolicies.forCluster(operatorCtx(m)))
        }
    }

    def job(ViewModel<Map> m) {
        guarded {
            Jobs.Job j = Jobs.get(p(m).id as String)
            if (!j) {
                return fail('No such job', 404)
            }
            // a job's log can name other tenants' VMs and disks: only operators of that job's cluster may read it
            if (j.clusterId != clusterId(m)) {
                return fail('No such job', 404)
            }
            operatorCtx(m)
            ok(j.toMap())
        }
    }

    /**
     * Read-only probe of every host against all linked arrays (portals of every array, lowest Purity version).
     * Runs synchronously but in parallel (the probe is quick). The checked hosts' compliance alarms follow the result.
     */
    def compliance(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            String only = p(m).host as String
            if (only) {
                ctx.requireHost(only)
            }
            List<Map> views = ComplianceMonitor.check(ctx, only)
            ComplianceMonitor.record(morpheus, ctx, views)
            ok(views)
        }
    }


    /** Every cluster host vs each linked array (host-group plan step per array) plus its view of each Pure GFS2 datastore. */
    def hosts(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            HostOnboarding hb = new HostOnboarding(morpheus, ctx)
            List<Map> iqns = ctx.iqnReport
            List<HostGroupPlanner.Node> nodes = PureClusterContext.nodesOf(iqns)
            Map<Long, HostGroupPlanner.Plan> plans = [:]
            List<Map> arrays = ctx.links.collect { Map l ->
                Long sid = l.storageServerId as Long
                Map a = [storageServerId: sid, recorded: l.hostGroup]
                try {
                    a.server = ctx.storageServer(sid).name
                    HostGroupPlanner.Plan plan = ctx.withArray(sid) { FlashArrayClient fa ->
                        new HostGroupApplier(fa).plan(ctx.cluster.name, nodes, l.hostGroup as String)
                    }
                    plans[sid] = plan
                    a += [hostGroup: plan.hostGroup, blockers: plan.blockers, strayHosts: plan.strayHosts]
                } catch (Exception e) {
                    a.error = e.message
                }
                a
            }
            List<Map> ds = hb.pureGfs2()
            Map<String, List<Map>> st = hb.storage(ctx.hostNames, ds)
            ok([arrays: arrays, datastores: ds*.name,
                hosts: ctx.hostNames.collect { String h ->
                    Map q = iqns.find { it.node == h } ?: [:]
                    [node: h, iqn: q.used, iqnRecord: q.record, iqnMismatch: q.mismatch ?: false,
                     arrays: arrays.collect { Map a ->
                         HostGroupPlanner.Plan plan = plans[a.storageServerId as Long]
                         HostGroupPlanner.HostStep s = plan?.hosts?.find { it.node == h }
                         [storageServerId: a.storageServerId, server: a.server, arrayHost: s?.arrayHost,
                          action: plan == null ? 'UNKNOWN' : (s?.action?.name() ?: 'UNKNOWN'),
                          detail: plan == null ? (a.error ?: 'array unreachable') : (s?.detail ?: 'no iSCSI initiator name found')]
                     },
                     storage: (st[h] ?: []).collect { it + [text: HostOnboarding.describe(it)] }]
                }])
        }
    }

    /** Adds a host to every linked array's host group, or only to storageServerId's. */
    def hostAdd(ViewModel<Map> m) {
        guarded {
            String host = p(m).host as String
            Long only = optionalLong(p(m).storageServerId)
            startJob(m, 'host-add') { PureClusterContext ctx, List<String> progress ->
                syncIqns(ctx, progress)
                [host: host, storage: new HostOnboarding(morpheus, ctx, progress).add(host, only)]
            }
        }
    }

    def hostMount(ViewModel<Map> m) {
        guarded {
            String host = p(m).host as String
            startJob(m, 'host-mount') { PureClusterContext ctx, List<String> progress ->
                ctx.requireHost(host)
                [host: host, storage: new HostOnboarding(morpheus, ctx, progress).mount(host)]
            }
        }
    }


    /** Adopts one datastore (datastoreId) or every adoptable one (all=true), on whichever linked array holds it. */
    def adopt(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            String only = p(m).datastoreId as String
            boolean all = flag(p(m).all)
            if (!only && !all) {
                throw new IllegalArgumentException('datastoreId or all=true is required')
            }
            List<Map> rows = adoptionRows(ctx)
            List<Map> chosen = all ? rows.findAll { it.state == 'adopt' } : rows.findAll { it.datastoreId as String == only }
            if (!chosen) {
                throw new IllegalArgumentException(all ? 'Nothing to adopt' : "Datastore ${only} is not a GFS2 datastore of this cluster")
            }
            chosen.each { Map r ->
                if (r.state != 'adopt') {
                    throw new IllegalStateException("${r.name}: ${r.state} (${r.detail}) - not adoptable")
                }
            }
            chosen.groupBy { it.storageServerId as Long }.each { Long sid, List<Map> rs ->
                ctx.withArray(sid) { FlashArrayClient fa -> rs.each { Adoption.adopt(fa, ctx.cluster.id, it) } }
            }
            audit(m, ctx, 'adopted datastore(s)', [datastores: chosen*.name.join(', ')])
            ok([adopted: chosen.collect { [name: it.name, volume: it.volume, storageServerId: it.storageServerId] }])
        }
    }

    // ------------------------------------------------------------------ change (jobs)

    /** Links the cluster to another array (or sets the host group of an existing link). */
    def link(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Long sid = optionalLong(p(m).storageServerId)
            StorageServer s = sid ? morpheus.services.storageServer.get(sid) : null
            if (s?.type?.code != PureStorageProvider.CODE) {
                throw new IllegalArgumentException('Not a Pure FlashArray (HVM) storage server')
            }
            ctx.setLink(sid, (p(m).hostGroup as String) ?: null)
            audit(m, ctx, 'linked FlashArray', [array: s.name, hostGroup: p(m).hostGroup])
            ok([linked: sid, links: ctx.links])
        }
    }

    /**
     * Forgets a link. Nothing on the array changes. Refused while this cluster still has datastore volumes or an open
     * recovery session on that array; a storage server that no longer exists can always be unlinked.
     */
    def unlink(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Long sid = optionalLong(p(m).storageServerId)
            if (!sid || !ctx.link(sid)) {
                throw new IllegalArgumentException('That array is not linked to this cluster')
            }
            if (sessions.values().any { it.clusterId == ctx.cluster.id && it.storageServerId == sid }) {
                throw new IllegalStateException('Close the open recovery session on this array first')
            }
            if (morpheus.services.storageServer.get(sid)) {
                List<String> vols = ctx.withArray(sid) { FlashArrayClient fa ->
                    fa.tagsByVolume().findAll { k, v -> PureNames.ownedBy(v, ctx.cluster.id) }*.key
                }
                if (vols) {
                    throw new IllegalStateException("This cluster still has datastore volume(s) on the array: ${vols.join(', ')}. " +
                            'Unlinking would hide them from grow, snapshots and recovery.')
                }
            }
            ctx.unlink(sid)
            audit(m, ctx, 'unlinked FlashArray', [storageServerId: sid])
            ok([unlinked: sid, links: ctx.links])
        }
    }

    def hostGroupApply(ViewModel<Map> m) {
        guarded {
            startArrayJob(m, 'host-group') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                HostGroupApplier ap = new HostGroupApplier(fa)
                List<Map> iqns = ctx.iqnReport
                syncIqns(ctx, progress, iqns)
                List<HostGroupPlanner.Node> nodes = PureClusterContext.nodesOf(iqns)
                HostGroupPlanner.Plan plan = ap.plan(ctx.cluster.name, nodes, (p(m).hostGroup as String) ?: link.hostGroup as String)
                String other = ctx.clusterUsing(link.storageServerId as Long, plan.hostGroup)
                if (other) {
                    throw new IllegalStateException("Host group ${plan.hostGroup} is already linked to cluster ${other}; refusing to change it")
                }
                progress.addAll(ap.apply(plan, nodes))
                ctx.setLink(link.storageServerId as Long, plan.hostGroup)
                progress << "cluster linked to host group ${plan.hostGroup}".toString()
                [storageServerId: link.storageServerId, hostGroup: plan.hostGroup]
            }
        }
    }

    def remediate(ViewModel<Map> m) {
        guarded {
            String host = p(m).host as String
            List<String> fixes = (p(m).fixes instanceof List ? p(m).fixes : (p(m).fixes as String)?.tokenize(',')) as List<String>
            startJob(m, 'remediate') { PureClusterContext ctx, List<String> progress ->
                ctx.requireHost(host)
                progress << "${host}: reading the host's current iSCSI/multipath state".toString()
                Map info = ComplianceMonitor.arrayFacts(ctx)
                HostRunner r = ctx.runner
                HostFacts f = HostFacts.parse(r.run(host, ComplianceMonitor.probe(info.portals as List<String>)).output)
                if (!f.complete) {
                    throw new IllegalStateException("${host}: the probe did not finish; nothing changed")
                }
                f.gatewayed.each { p, via -> progress << "${host}: portal ${p} is only reachable through a gateway (${via}); left alone".toString() }
                List<Map<String, String>> steps = new Remediation().scriptSteps(f, fixes, info.portals as List<String>, info.purity as String)
                List<String> applied = []
                steps.eachWithIndex { Map<String, String> s, int i ->
                    progress << "${host}: [${i + 1}/${steps.size()}] ${s.title}...".toString()
                    HostRunner.Result res = r.run(host, s.script)
                    List<String> lines = (res.output ?: '').readLines().findAll { it.trim() && !it.startsWith('== ') && !it.startsWith('ERROR=') }
                    lines.each { progress << "    ${it}".toString() }
                    if (!res.ok) {
                        progress << "    ${res.error ?: ''}".toString()
                        throw new IllegalStateException("${host}: step '${s.title}' failed (exit ${res.exitCode})" +
                                (applied ? "; already applied: ${applied.join(', ')}" : '; nothing applied before it'))
                    }
                    applied << s.title
                }
                syncIqns(ctx, progress)   // a new initiator name must reach the Morpheus record too
                progress << "${host}: done; run Check again to see the result".toString()
                [host: host, applied: applied]
            }
        }
    }

    def datastoreCreate(ViewModel<Map> m) {
        guarded {
            String name = p(m).name as String
            long bytes = bytesOf(m)
            startArrayJob(m, 'datastore-create') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                requireHostGroup(link)
                progress << "array ${ctx.storageServer(link.storageServerId as Long).name}, host group ${link.hostGroup}".toString()
                DatastoreOps.Provisioned pr = new DatastoreOps(fa, ctx.runner, ctx.hostNames, registrar(), progress)
                        .provisionGfs2(ctx.cluster.id, link.hostGroup as String, name, bytes)
                // core fills in size and free space only on its own schedule; measure now (never fails the job)
                refreshCapacity(fa, ctx, pr.volume, progress)
                [volume: pr.volume, devicePath: pr.devicePath, datastoreId: pr.datastoreId, storageServerId: link.storageServerId]
            }
        }
    }

    def datastoreGrow(ViewModel<Map> m) {
        guarded {
            String vol = p(m).volume as String
            long bytes = bytesOf(m)
            startArrayJob(m, 'datastore-grow') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                requireClusterVolume(fa, ctx, vol)
                Map g = new DatastoreOps(fa, ctx.runner, ctx.hostNames, null, progress).grow(ctx.cluster.id, vol, bytes)
                g.morpheus = refreshCapacity(fa, ctx, vol, progress)
                g
            }
        }
    }

    /**
     * Measures the GFS2 size on a host and brings the Morpheus datastore record up to date (after create, grow or a
     * refresh). Never throws: a stale capacity figure is a warning, not a failed operation.
     */
    private Map refreshCapacity(FlashArrayClient fa, PureClusterContext ctx, String vol, List<String> progress) {
        try {
            String dsId = fa.volumeTags(vol)['morpheus-datastore-id']
            if (!dsId) {
                progress << "WARNING: ${vol} has no Morpheus datastore id tag; capacity not refreshed".toString()
                return null
            }
            String wwid = volumeWwid(fa, vol)
            HostRunner r = ctx.runner
            HostRunner.Result res = ctx.hostNames.collect { r.run(it, HostScripts.fsSize(wwid)) }.find { it.ok }
            if (!res) {
                progress << 'WARNING: datastore is not mounted on any host; capacity not refreshed'
                return null
            }
            Map u = MorpheusCapacity.update(morpheus, dsId as Long, res.kv.FSSIZE as Long, res.kv.FSFREE as Long, res.kv.MOUNT)
            progress << "Morpheus datastore ${u.datastore}: ${u.before} -> ${u.after} bytes (${u.method})".toString()
            return u
        } catch (Exception e) {
            log.warn("capacity refresh for ${vol} failed", e)
            progress << "WARNING: Morpheus capacity refresh failed (${e.message}); the datastore itself is fine".toString()
            return null
        }
    }

    def datastoreRefresh(ViewModel<Map> m) {
        guarded {
            String vol = p(m).volume as String
            startArrayJob(m, 'datastore-refresh') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                requireClusterVolume(fa, ctx, vol)
                refreshCapacity(fa, ctx, vol, progress) ?: [refreshed: false]
            }
        }
    }

    def snapshotCreate(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            String vol = p(m).volume as String
            Map r = ctx.withArray(linkFor(ctx, m).storageServerId as Long) { FlashArrayClient fa ->
                requireClusterVolume(fa, ctx, vol)
                [snapshot: new RecoveryOps(fa, ctx.runner).snapshot(vol, (p(m).suffix as String) ?: null)]
            }
            audit(m, ctx, 'took array snapshot', [volume: vol, snapshot: r.snapshot instanceof Map ? (r.snapshot as Map).name : r.snapshot])
            ok(r)
        }
    }

    def recoveryOpen(ViewModel<Map> m) {
        guarded {
            String vol = p(m).volume as String
            String snap = p(m).snapshot as String
            String host = p(m).host as String
            startArrayJob(m, 'recovery-open') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                requireClusterVolume(fa, ctx, vol)
                String node = host ?: ctx.hostNames.find()
                String arrayHost = ctx.arrayHostFor(fa, node)
                long now = System.currentTimeMillis()
                long expires = now + recoveryTimeoutHours() * RecoverySessions.HOUR_MS
                RecoveryOps.Session s = new RecoveryOps(fa, ctx.runner, progress).openRecovery(vol, snap, node, arrayHost, ctx.cluster.id, expires)
                Map entry = [clusterId: ctx.cluster.id, storageServerId: link.storageServerId as Long, session: s, created: now, expires: expires]
                sessions[s.tag] = entry
                progress << "closes automatically at ${new Date(expires).toInstant()} unless kept open".toString()
                sessionView(entry)
            }
        }
    }

    /**
     * The session a request names; rebuilt from the array if the cache does not have it (plugin reloaded). Callers
     * get {@code ctx} from operatorCtx: recovery sessions expose every tenant's disks, so operators only.
     */
    private Map session(PureClusterContext ctx, ViewModel<Map> m) {
        String tag = p(m).session as String
        Map entry = sessions[tag]
        if (!entry) {
            RecoverySessions.refresh(ctx, recoveryTimeoutHours())
            entry = sessions[tag]
        }
        if (!entry || entry.clusterId != ctx.cluster.id) {
            throw new IllegalArgumentException("Unknown recovery session ${tag}: its clone is no longer on the array")
        }
        entry
    }

    /** "Keep open": push the automatic close out by one timeout from now. */
    def recoveryExtend(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Map entry = session(ctx, m)
            long until = ctx.withArray(entry.storageServerId as Long) { FlashArrayClient fa -> RecoverySessions.extend(fa, entry, recoveryTimeoutHours()) }
            audit(m, ctx, 'kept recovery session open', [session: entry.session?.tag, until: new Date(until).toInstant()])
            ok(sessionView(entry) + [expires: until])
        }
    }

    /** A job on the array a recovery session was opened on. */
    private JsonResponse startSessionJob(ViewModel<Map> m, String kind, Closure work) {
        PureClusterContext ctx = operatorCtx(m)
        Map entry = session(ctx, m)
        auditedJob(m, ctx, kind) { List<String> progress ->
            ctx.withArray(entry.storageServerId as Long) { FlashArrayClient fa ->
                work.call(ctx, fa, entry.session as RecoveryOps.Session, progress)
            }
        }
    }

    def recoveryRestore(ViewModel<Map> m) {
        guarded {
            startSessionJob(m, 'recovery-restore') { PureClusterContext ctx, FlashArrayClient fa, RecoveryOps.Session s, List<String> progress ->
                new RecoveryOps(fa, ctx.runner, progress).restoreDisk(s, p(m).path as String, volumeWwid(fa, s.sourceVolume), p(m).destName as String)
            }
        }
    }

    /** VMs of this cluster ([serverId, name, domain, host, power]); the libvirt domain is the server's externalId. */
    private static List<Map> clusterVms(PureClusterContext ctx) {
        Map<Long, String> hostById = ctx.hosts.collectEntries { [(it.id): it.name] }
        ctx.vms.collect { ComputeServer s ->
            [serverId: s.id, name: s.name, domain: s.externalId ?: s.name, host: hostById[s.parentServer.id], power: s.powerState?.toString()]
        }
    }

    private static String prodMount(FlashArrayClient fa, PureClusterContext ctx, RecoveryOps.Session s) {
        RecoveryOps.mountOf(ctx.runner, s.host, volumeWwid(fa, s.sourceVolume))
    }

    def recoveryVms(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Map entry = session(ctx, m)
            RecoveryOps.Session s = entry.session as RecoveryOps.Session
            ok(ctx.withArray(entry.storageServerId as Long) { FlashArrayClient fa ->
                new RecoveryOps(fa, ctx.runner).vmsInSession(s, prodMount(fa, ctx, s), clusterVms(ctx))
            })
        }
    }

    def recoveryRestoreVm(ViewModel<Map> m) {
        guarded {
            Long serverId = requiredLong(m, 'serverId')   // before the job: a bad request must not take the cluster lock
            startSessionJob(m, 'restore-vm') { PureClusterContext ctx, FlashArrayClient fa, RecoveryOps.Session s, List<String> progress ->
                RecoveryOps ops = new RecoveryOps(fa, ctx.runner, progress)
                String pm = prodMount(fa, ctx, s)
                Map vm = ops.vmsInSession(s, pm, clusterVms(ctx).findAll { it.serverId == serverId }).find()
                if (!vm) {
                    throw new IllegalArgumentException("Server ${serverId} has no disks on this datastore")
                }
                if (vm.power && vm.power != 'off') {
                    throw new IllegalStateException("${vm.name} must be stopped in Morpheus first (power state ${vm.power})")
                }
                progress << "restoring ${vm.name} (${(vm.disks as List).size()} disk(s)) from ${s.snapshot}".toString()
                ops.restoreVmInPlace(s, pm, vm)
            }
        }
    }

    def recoveryClose(ViewModel<Map> m) {
        guarded {
            String tag = p(m).session as String
            startSessionJob(m, 'recovery-close') { PureClusterContext ctx, FlashArrayClient fa, RecoveryOps.Session s, List<String> progress ->
                new RecoveryOps(fa, ctx.runner, progress).closeRecovery(s)
                sessions.remove(tag)
                [closed: tag]
            }
        }
    }

    def cloneDatastore(ViewModel<Map> m) {
        guarded {
            if (!experimental()) {
                throw new IllegalStateException('Clone-as-datastore is experimental: enable it in the plugin settings after validating it in a test environment')
            }
            String vol = p(m).volume as String
            String snap = p(m).snapshot as String
            String name = p(m).name as String
            startArrayJob(m, 'clone-datastore') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                requireHostGroup(link)
                requireClusterVolume(fa, ctx, vol)
                String node = (p(m).host as String) ?: ctx.hostNames.find()
                new RecoveryOps(fa, ctx.runner, progress).cloneAsDatastore(ctx.cluster.id, link.hostGroup as String, ctx.hostNames, vol, snap, name,
                        node, ctx.arrayHostFor(fa, node), registrar())
            }
        }
    }

    // ------------------------------------------------------------------ self-service VM restore (instance page)

    /** A required id on a customer-facing route (its message is written for the customer). */
    private static Long vmLong(ViewModel<Map> m, String name) {
        Long v = optionalLong(p(m)[name])
        if (!v) {
            throw new VmRestore.PublicError("${name} is required")
        }
        v
    }

    private VmRestore.Target vmTarget(ViewModel<Map> m, String param = 'instanceId') {
        new VmRestore(morpheus).resolve(vmLong(m, param), m.user, Tenancy.operatorTenantIds(settings()))
    }

    /** The audit details every VM route records. */
    private static Map vmDetails(VmRestore.Target t) {
        [vm: t.instance.name, tenant: t.instance.account?.name]
    }

    /**
     * Like guarded, for the customer-facing VM routes: anything not written for customers (array, host, datastore
     * names in the message) is logged and replaced by a generic message.
     */
    private JsonResponse vmGuarded(String generic, Closure<JsonResponse> c) {
        guarded {
            try {
                c.call()
            } catch (Exception e) {
                if (VmRestore.isPublic(e)) {
                    throw e
                }
                log.warn('Pure VM restore request failed', e)
                throw new VmRestore.PublicError(generic)
            }
        }
    }

    private static final String POINTS_UNAVAILABLE = 'Restore points are not available right now; your provider has the details.'

    /** The VM, its disks and the times it can be restored to (opaque ids: no snapshot or storage names). */
    def vmRestorePoints(ViewModel<Map> m) {
        vmGuarded(POINTS_UNAVAILABLE) {
            VmRestore.Target t = vmTarget(m)
            VmRestore vr = new VmRestore(morpheus)
            Map s = settings()
            vr.copyHours = FileRestoreCopies.defaultHours(s)
            Map info = null
            try {
                info = vr.tabInfo(t)
            } catch (Exception e) {
                log.warn("restore tab info of ${t.instance.name}: ${e.message}")
            }
            String schedule = null
            List<Map> points = t.ctx.withArray(t.array.storageServerId as Long) { FlashArrayClient fa ->
                try {
                    schedule = SnapshotPolicies.customerText(SnapshotPolicies.forVolumes(fa, [t.array.volume as String]).find()?.policies as List<Map>)
                } catch (Exception e) {
                    log.warn("snapshot schedule of ${t.instance.name}: ${e.message}")
                }
                vr.restorePoints(t, fa, info)
            }
            ok(t.toView() + [points: points.take(SnapshotIndex.MAX_POINTS), schedule: schedule, copies: info?.copies, network: VmRestore.networkState(info?.nics as List<Map>),
                             copyHours: [default: vr.copyHours, max: FileRestoreCopies.maxHours(s)]])
        }
    }

    /**
     * Runs a VM restore job. The customer sees the job's progress and, on failure, only a message written for them
     * (VmRestore.publicMessage, with a reference); the operators' audit gets the snapshot used and the real error.
     */
    private JsonResponse startVmJob(ViewModel<Map> m, String kind, VmRestore.Target t, List<Long> instanceIds, Map details,
                                    Closure<Object> work) {
        User who = m.user
        String ref = UUID.randomUUID().toString().take(8)
        Map audited = details + [ref: ref]
        Audit.info(morpheus, who, t.ctx.cluster, "${kind} started", audited)
        vmJobOk(Jobs.start(kind, t.ctx.cluster.id, user(m), [instanceIds: instanceIds]) { List<String> progress ->
            VmRestore vr = new VmRestore(morpheus, progress)
            try {
                Object r = t.ctx.withArray(t.array.storageServerId as Long) { FlashArrayClient fa -> work.call(vr, fa) }
                Audit.info(morpheus, who, t.ctx.cluster, "${kind} done", audited + [snapshot: vr.snapshotUsed])
                r
            } catch (Throwable e) {
                log.warn("${kind} ${ref} failed; detail: ${vr.detail.join(' | ')}", e)
                Audit.warn(morpheus, who, t.ctx.cluster, "${kind} failed: ${e.message}", audited + [snapshot: vr.snapshotUsed])
                throw new VmRestore.PublicError(VmRestore.publicMessage(e, ref))
            }
        })
    }

    /** A VM job as customers see it: no cluster. */
    private static JsonResponse vmJobOk(Jobs.Job j) {
        ok(j.toMap().findAll { it.key != 'clusterId' })
    }

    private static List<Long> longList(Object v) {
        (v == null || v == '') ? [] : (v.toString().split(',').collect { it.trim() }.findAll() as List<String>).collect { it as Long }
    }

    /**
     * Overwrite: restore the (powered-off) VM in place from restorePoint - every disk, or only volumeIds (comma list
     * of its Morpheus disk ids); the replaced disks are kept as .pre-restore copies.
     */
    def vmRestore(ViewModel<Map> m) {
        vmGuarded(VmRestore.publicMessage(null)) {
            VmRestore.Target t = vmTarget(m)
            String point = p(m).restorePoint as String
            List<Long> vols = longList(p(m).volumeIds)
            startVmJob(m, 'vm-restore', t, [t.instance.id],
                    vmDetails(t) + [restorePoint: point, mode: 'overwrite', disks: vols ? vols.join(',') : 'all']) { VmRestore vr, FlashArrayClient fa ->
                vr.overwrite(t, fa, point, vols ?: null)
            }
        }
    }

    /** Add as a new disk: one disk as of restorePoint copied into newVolumeId, an empty disk just added to the same VM. */
    def vmAddDisk(ViewModel<Map> m) {
        vmGuarded(VmRestore.publicMessage(null)) {
            VmRestore.Target t = vmTarget(m)
            String point = p(m).restorePoint as String
            Long from = vmLong(m, 'sourceVolumeId'), into = vmLong(m, 'newVolumeId')
            startVmJob(m, 'vm-add-disk', t, [t.instance.id],
                    vmDetails(t) + [restorePoint: point, mode: 'add-disk', sourceVolumeId: from, newVolumeId: into]) { VmRestore vr, FlashArrayClient fa ->
                vr.addDisk(t, fa, point, from, into)
            }
        }
    }

    /** File-level restore: a temporary copy of one disk as of restorePoint, hot-attached to the running VM. */
    def vmFileRestore(ViewModel<Map> m) {
        vmGuarded(VmRestore.publicMessage(null)) {
            VmRestore.Target t = vmTarget(m)
            String point = p(m).restorePoint as String
            Long from = vmLong(m, 'sourceVolumeId')
            long hours = FileRestoreCopies.clamp(p(m).hours, settings())
            startVmJob(m, 'vm-file-restore', t, [t.instance.id],
                    vmDetails(t) + [restorePoint: point, mode: 'file-restore', sourceVolumeId: from, hours: hours]) { VmRestore vr, FlashArrayClient fa ->
                vr.attachCopy(t, fa, point, from, hours)
            }
        }
    }

    /** Keeps a file-restore copy for {@code hours} more from now (capped at the maximum; default = the default lifetime). */
    def vmFileRestoreExtend(ViewModel<Map> m) {
        vmGuarded('The copy could not be kept longer; your provider has the details.') {
            VmRestore.Target t = vmTarget(m)
            long hours = FileRestoreCopies.clamp(p(m).hours, settings())
            Map r = new VmRestore(morpheus).extendCopy(t, p(m).copy as String, hours)
            audit(m, t.ctx, 'vm-file-restore kept longer', vmDetails(t) + [copy: r.copy, until: new Date(r.expires as Long).toInstant()])
            ok(r)
        }
    }

    /** Detaches and deletes one file-restore copy (copy = its name from vm-restore-points). */
    def vmFileRestoreRemove(ViewModel<Map> m) {
        vmGuarded('The copy could not be removed; your provider has the details.') {
            VmRestore.Target t = vmTarget(m)
            Map r = new VmRestore(morpheus).removeCopy(t, p(m).copy as String)
            audit(m, t.ctx, 'vm-file-restore removed', vmDetails(t) + [copy: r.removed])
            ok(r)
        }
    }

    /** Clone to new VM: the restore point's disks go into targetInstanceId (just provisioned by the user, powered off). */
    def vmClone(ViewModel<Map> m) {
        vmGuarded(VmRestore.publicMessage(null)) {
            VmRestore.Target src = vmTarget(m)
            VmRestore.Target dst = vmTarget(m, 'targetInstanceId')
            if (dst.instance.account?.id != src.instance.account?.id) {
                throw new SecurityException('The new VM must belong to the same tenant as the original')
            }
            String point = p(m).restorePoint as String
            boolean connect = flag(p(m).connectNetwork)
            startVmJob(m, 'vm-clone', src, [src.instance.id, dst.instance.id],
                    vmDetails(src) + [newVm: dst.instance.name, restorePoint: point, mode: 'clone', connectNetwork: connect]) { VmRestore vr, FlashArrayClient fa ->
                vr.cloneInto(src, dst, fa, point, connect)
            }
        }
    }

    /** Connects (connect=true) or disconnects every network interface of the VM; for a restored clone kept off the network. */
    def vmNetwork(ViewModel<Map> m) {
        vmGuarded('The network could not be changed; your provider has the details.') {
            VmRestore.Target t = vmTarget(m)
            boolean connect = flag(p(m).connect)
            Map r = new VmRestore(morpheus).setNetwork(t, connect)
            audit(m, t.ctx, "vm-network ${connect ? 'connected' : 'disconnected'}".toString(), vmDetails(t))
            ok(r)
        }
    }

    /** A restore job, for whoever may restore that VM (customers cannot use /job: it is operator-only). */
    def vmJob(ViewModel<Map> m) {
        vmGuarded(POINTS_UNAVAILABLE) {
            VmRestore.Target t = vmTarget(m)
            Jobs.Job j = Jobs.get(p(m).id as String)
            if (!j || !(j.kind in VM_JOB_KINDS) || !((j.meta?.instanceIds ?: []) as List).contains(t.instance.id)) {
                return fail('No such job', 404)
            }
            vmJobOk(j)
        }
    }

    // ------------------------------------------------------------------ array hosts / rescan

    /**
     * Read-only: for each cluster node, its real and recorded IQN, and every array host on each linked array that
     * matches it by either IQN or by name - with its host group and the volumes it sees (via group or privately).
     */
    def arrayHosts(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            List<Map> iqns = ctx.iqnReport
            ok([nodes: iqns, arrays: ctx.links.collect { Map l ->
                Long sid = l.storageServerId as Long
                try {
                    ctx.withArray(sid) { FlashArrayClient fa ->
                        List<Map> hosts = fa.listHosts()
                        List<Map> related = hosts.findAll { Map h ->
                            List<String> hq = ((h.iqns ?: []) as List<String>)*.toLowerCase()
                            iqns.any { Map q ->
                                String shortName = HostGroupPlanner.arrayHostName(q.node as String).toLowerCase()
                                hq.contains((q.actual as String)?.toLowerCase()) || hq.contains((q.record as String)?.toLowerCase()) ||
                                        (h.name as String).toLowerCase().startsWith(shortName)
                            }
                        }
                        List<Map> conns = related ? fa.listConnections([host_names: related*.name]) : []
                        [storageServerId: sid, server: ctx.storageServer(sid).name, recordedHostGroup: l.hostGroup,
                         hosts: related.collect { Map h ->
                             List<String> hq = ((h.iqns ?: []) as List<String>)
                             [name: h.name, iqns: hq, hostGroup: (h.host_group as Map)?.name,
                              matches: iqns.findAll { Map q -> hq*.toLowerCase().contains((q.actual as String)?.toLowerCase()) }*.node,
                              staleFor: iqns.findAll { Map q -> q.mismatch && hq*.toLowerCase().contains((q.record as String)?.toLowerCase()) }*.node,
                              volumes: conns.findAll { it.host == h.name }.collect { [volume: it.volume, lun: it.lun, via: it.hostGroup ?: 'private'] }]
                         }]
                    }
                } catch (Exception e) {
                    [storageServerId: sid, error: e.message]
                }
            }])
        }
    }

    /**
     * Deletes an array host that only carries a STALE IQN of one of this cluster's nodes (the Morpheus record's IQN,
     * which the host no longer uses) and sees no volumes - e.g. <host>-iscsi made from a rebuilt host's old IQN.
     * Then drops its host group if that is now empty, unconnected and no other cluster's, and clears this cluster's
     * recorded host group if it pointed there.
     */
    def arrayHostRemove(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Map link = linkFor(ctx, m)
            Long sid = link.storageServerId as Long
            String name = p(m).host as String
            List<Map> iqns = ctx.iqnReport
            Set<String> real = iqns.collect { (it.actual as String)?.toLowerCase() }.findAll() as Set<String>
            Set<String> stale = iqns.findAll { it.mismatch }.collect { (it.record as String).toLowerCase() } as Set<String>
            ok(ctx.withArray(sid) { FlashArrayClient fa ->
                Map h = fa.listHosts().find { it.name == name }
                if (!h) {
                    throw new IllegalArgumentException("No array host ${name}")
                }
                List<String> hq = ((h.iqns ?: []) as List<String>)*.toLowerCase()
                if (hq.any { real.contains(it) }) {
                    throw new IllegalStateException("${name} carries the IQN a cluster host really uses; refusing")
                }
                if (!hq || !hq.every { stale.contains(it) }) {
                    throw new IllegalStateException("${name} is not a stale copy of a node of this cluster (its IQNs: ${hq.join(', ') ?: 'none'}); remove it on the array if it is unused")
                }
                if (fa.listConnections([host_names: name])) {
                    throw new IllegalStateException("${name} still sees volumes; refusing")
                }
                List<String> done = []
                String group = (h.host_group as Map)?.name
                if (group) {
                    fa.removeHostFromHostGroup(group, name)
                    done << "removed ${name} from ${group}".toString()
                }
                fa.deleteHost(name)
                done << "deleted array host ${name}".toString()
                if (group && !fa.listHostGroupMembers([group]) && !fa.listConnections([host_group_names: group]) && !ctx.clusterUsing(sid, group)) {
                    fa.deleteHostGroup(group)
                    done << "deleted empty host group ${group}".toString()
                    if (link.hostGroup == group) {
                        ctx.clearHostGroup(sid)
                        done << "cleared this cluster's recorded host group (was ${group}); use the Hosts section to pick it again".toString()
                    }
                }
                audit(m, ctx, 'removed stale array host', [host: name, steps: done.join('; ')])
                [done: done]
            })
        }
    }

    /** Brings Morpheus's IQN records in line with the hosts; never fails the job it runs in. */
    private static void syncIqns(PureClusterContext ctx, List<String> progress, List<Map> report = null) {
        try {
            ctx.syncIqnRecords(report).each { progress << it }
        } catch (Exception e) {
            progress << "WARNING: could not update Morpheus IQN records: ${e.message}".toString()
        }
    }

    /** "Update Morpheus record" in the Hosts table: write the hosts' real IQNs into their Morpheus server records now. */
    def iqnSync(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            List<String> changed = ctx.syncIqnRecords()
            if (changed) {
                audit(m, ctx, 'updated Morpheus IQN record(s)', [changes: changed.join('; ')])
            }
            ok([changed: changed])
        }
    }

    /** Rescan iSCSI and multipath on one host (adds new LUNs and paths; never flushes or reloads existing maps). */
    def hostRescan(ViewModel<Map> m) {
        guarded {
            String host = p(m).host as String
            startJob(m, 'host-rescan') { PureClusterContext ctx, List<String> progress ->
                ctx.requireHost(host)
                syncIqns(ctx, progress)
                HostRunner.Result rs = ctx.runner.run(host, HostScripts.rescanAll())
                if (!rs.ok) {
                    throw new IllegalStateException("${host}: rescan failed: ${rs.error}")
                }
                rs.all('STALE_REMOVED').each { progress << "${host}: removed dead map ${it} (all paths failed, not mounted or open)".toString() }
                rs.all('WARN').each { progress << "${host}: ${it}".toString() }
                progress << "${host}: rescanned - ${rs.kv.SESSIONS ?: 0} FlashArray iSCSI session(s), ${rs.kv.PURE_MAPS ?: 0} FlashArray LUN(s) visible".toString()
                HostOnboarding hb = new HostOnboarding(morpheus, ctx, progress)
                List<Map> st = hb.storage([host], hb.pureGfs2())[host]
                st.each { progress << "${host}: ${it.name} - ${HostOnboarding.describe(it)}".toString() }
                [host: host, sessions: rs.kv.SESSIONS, maps: rs.kv.PURE_MAPS, storage: st]
            }
        }
    }

    // ------------------------------------------------------------------ retire

    /**
     * Read-only Retire plan for one datastore: its disks (all tenants), files on the mount, targets and the auto pick.
     * The tab runs the Morpheus-side steps itself, as the user; see RetireOps.
     */
    def retirePlan(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Long dsId = requiredLong(m, 'datastoreId')
            List<String> open = RecoverySessions.refresh(ctx, recoveryTimeoutHours()).collect { (it.session as RecoveryOps.Session).sourceVolume }
            ok(new RetireOps(morpheus, ctx).plan(dsId, open))
        }
    }

    /** After Morpheus deleted the datastore: hosts first, then disconnect and destroy (never eradicate) the LUN. */
    def retireFinish(ViewModel<Map> m) {
        guarded {
            Long dsId = requiredLong(m, 'datastoreId')
            String vol = p(m).volume as String
            if (!vol) {
                throw new IllegalArgumentException('volume is required')
            }
            boolean leftovers = flag(p(m).allowLeftovers)
            boolean deleteFirst = flag(p(m).deleteInMorpheus)
            startArrayJob(m, 'retire-finish') { PureClusterContext ctx, Map link, FlashArrayClient fa, List<String> progress ->
                new RetireOps(morpheus, ctx, progress).finish(fa, dsId, vol, leftovers, 600, deleteFirst)
            }
        }
    }


    /** Which tenants a datastore is shared with (its tenant entries). */
    def datastoreTenants(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Datastore ds = ctx.datastore(requiredLong(m, 'datastoreId'))
            ok([datastoreId: ds.id, name: ds.name, ownerId: ds.owner?.id, tenants: DatastoreTenancy.tenantsOf(morpheus, ds)])
        }
    }

    /**
     * Share a datastore with a tenant, or stop sharing (op=add|remove, tenantId). In-process: a tenant user's own
     * /api edit of a datastore strips its tenant entries on 9.1, and a master token cannot reach a tenant's cluster.
     */
    def datastoreShare(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Datastore ds = ctx.datastore(requiredLong(m, 'datastoreId'))
            Long tenantId = requiredLong(m, 'tenantId')
            boolean changed = (p(m).op == 'remove') ?
                    DatastoreTenancy.removeAccountPermission(morpheus, ds, tenantId) :
                    DatastoreTenancy.ensureAccountPermission(morpheus, ds, new Account(id: tenantId))
            if (changed) {
                audit(m, ctx, p(m).op == 'remove' ? 'stopped sharing datastore with tenant' : 'shared datastore with tenant', [datastore: ds.name, tenantId: tenantId])
            }
            ok([changed: changed, tenants: DatastoreTenancy.tenantsOf(morpheus, ds)])
        }
    }

    // ------------------------------------------------------------------ reclaim

    /** Read-only: restore leftovers, other files and detached Morpheus disks on each GFS2 datastore of the cluster. */
    def reclaimScan(ViewModel<Map> m) {
        guarded {
            ok(new Reclaim(morpheus, operatorCtx(m)).scan())
        }
    }

    /** Deletes restore leftovers (paths: comma list under the datastore's mount) that no VM uses, as a cluster job. */
    def reclaimDelete(ViewModel<Map> m) {
        guarded {
            Long dsId = requiredLong(m, 'datastoreId')
            List<String> paths = (p(m).paths instanceof List ? p(m).paths : (p(m).paths as String)?.tokenize(',')) as List<String>
            startJob(m, 'reclaim') { PureClusterContext ctx, List<String> progress ->
                new Reclaim(morpheus, ctx, progress).delete(dsId, (paths ?: [])*.trim().findAll() as List<String>)
            }
        }
    }

    /** Drain / undrain / heartbeat hand-over for Retire, in-process (see RetireOps.morpheusAction for why). */
    def retireMorpheus(ViewModel<Map> m) {
        guarded {
            PureClusterContext ctx = operatorCtx(m)
            Long dsId = requiredLong(m, 'datastoreId')
            // 'op', not 'action': Morpheus replaces a request parameter named action with its own route action ('route')
            String msg = new RetireOps(morpheus, ctx).morpheusAction(p(m).op as String, dsId, optionalLong(p(m).targetId))
            audit(m, ctx, "retire: ${msg}".toString(), [datastoreId: dsId])
            ok([done: msg])
        }
    }

    // ------------------------------------------------------------------ internals

    private static Map sessionView(Map entry) {
        RecoveryOps.Session s = entry.session as RecoveryOps.Session
        [session: s.tag, storageServerId: entry.storageServerId, volume: s.volume, host: s.host, mount: s.mount,
         sourceVolume: s.sourceVolume, snapshot: s.snapshot, files: s.files, created: entry.created, expires: entry.expires]
    }

    /** Only volumes this plugin created for THIS cluster may be operated on from its tab. */
    private static void requireClusterVolume(FlashArrayClient fa, PureClusterContext ctx, String vol) {
        if (!PureNames.ownedBy(fa.volumeTags(vol), ctx.cluster.id)) {
            throw new IllegalArgumentException("${vol} is not a datastore volume of cluster ${ctx.cluster.name} on this array")
        }
    }
}
