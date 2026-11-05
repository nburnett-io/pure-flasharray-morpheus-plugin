package org.purehvm.morpheus

import com.morpheusdata.core.AbstractTaskService
import com.morpheusdata.core.ExecutableTaskInterface
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.TaskProvider
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Container
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Task
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.TaskType
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import org.purehvm.api.FlashArrayClient
import org.purehvm.ops.RecoveryOps
import groovy.util.logging.Slf4j

/**
 * Workflow task "Pure FlashArray snapshot": an array snapshot of the GFS2 datastore(s) the VM's disks are on, for
 * example before a change in a provisioning or operational workflow. Optionally application-consistent (guests on
 * that datastore frozen first, see ConsistentSnapshot). The snapshot covers the whole datastore, so every VM on it.
 * It is an ordinary array snapshot: it shows in the cluster tab and as a restore point, and the array's own
 * eradication rules apply to it.
 */
@Slf4j
class PureSnapshotTaskProvider implements TaskProvider {
    static final String CODE = 'pure-flasharray-hvm.snapshotTask'
    static final String OPT_FREEZE = 'pureTaskFreeze'
    static final String OPT_SUFFIX = 'pureTaskSuffix'

    final Plugin plugin
    final MorpheusContext morpheus

    PureSnapshotTaskProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { CODE }
    @Override String getName() { 'Pure FlashArray snapshot' }
    @Override String getDescription() { 'Array snapshot of the GFS2 datastore the VM is on (optionally application-consistent)' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }
    /**
     * Core runs a plugin task through this service (seen on 9.1: with none, every run fails at once with no output), so
     * it hands each call back to the provider. Container is the old name of Workload.
     */
    @Override
    ExecutableTaskInterface getService() {
        PureSnapshotTaskProvider p = this
        new AbstractTaskService() {
            @Override MorpheusContext getMorpheus() { p.morpheus }
            @Override TaskResult executeLocalTask(Task task, Map opts, Container container, ComputeServer server, Instance instance) { p.run(task, server ?: container?.server ?: p.serverOf(instance), runnerOf(opts)) }
            @Override TaskResult executeServerTask(ComputeServer server, Task task, Map opts) { p.run(task, server, runnerOf(opts)) }
            @Override TaskResult executeServerTask(ComputeServer server, Task task) { p.run(task, server) }
            @Override TaskResult executeContainerTask(Container container, Task task, Map opts) { p.run(task, container?.server, runnerOf(opts)) }
            @Override TaskResult executeContainerTask(Container container, Task task) { p.run(task, container?.server) }
            @Override TaskResult executeRemoteTask(Task task, Map opts, Container container, ComputeServer server, Instance instance) { executeLocalTask(task, opts, container, server, instance) }
            @Override TaskResult executeRemoteTask(Task task, Container container, ComputeServer server, Instance instance) { executeLocalTask(task, [:], container, server, instance) }
        }
    }
    @Override TaskType.TaskScope getScope() { TaskType.TaskScope.all }
    @Override Boolean isAllowExecuteLocal() { true }
    @Override Boolean isAllowExecuteRemote() { false }
    @Override Boolean isAllowExecuteResource() { true }
    @Override Boolean isAllowLocalRepo() { false }
    @Override Boolean isAllowRemoteKeyAuth() { false }
    @Override Boolean hasResults() { true }
    @Override Icon getIcon() { new Icon(path: 'pure.svg', darkPath: 'pure-dark.svg') }

    @Override
    List<OptionType> getOptionTypes() {
        [
                new OptionType(code: OPT_FREEZE, name: 'Application-consistent', fieldName: OPT_FREEZE, fieldContext: 'taskOption',
                        fieldLabel: 'Freeze guests first (application-consistent)', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 0,
                        helpText: 'Freezes the file systems of every running VM on the datastore (qemu guest agent) for the moment of the snapshot. VMs without an agent are crash-consistent.'),
                new OptionType(code: OPT_SUFFIX, name: 'Snapshot suffix', fieldName: OPT_SUFFIX, fieldContext: 'taskOption',
                        fieldLabel: 'Snapshot suffix', inputType: OptionType.InputType.TEXT, required: false, displayOrder: 1,
                        helpText: 'Optional: 1-63 letters, digits or -, with at least one letter. Empty: the array numbers the snapshot.'),
        ]
    }

    @Override
    TaskResult executeLocalTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance) {
        run(task, server ?: workload?.server ?: serverOf(instance), runnerOf(opts))
    }

    @Override TaskResult executeServerTask(ComputeServer server, Task task, Map opts) { run(task, server, runnerOf(opts)) }
    @Override TaskResult executeServerTask(ComputeServer server, Task task) { run(task, server) }
    @Override TaskResult executeContainerTask(Workload workload, Task task, Map opts) { run(task, workload?.server, runnerOf(opts)) }
    @Override TaskResult executeContainerTask(Workload workload, Task task) { run(task, workload?.server) }
    @Override TaskResult executeRemoteTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance) { executeLocalTask(task, opts, workload, server, instance) }
    @Override TaskResult executeRemoteTask(Task task, Workload workload, ComputeServer server, Instance instance) { executeLocalTask(task, [:], workload, server, instance) }

    ComputeServer serverOf(Instance instance) {
        Long id = (instance?.containers ?: []).collect { it.server?.id }.find()
        id ? morpheus.services.computeServer.get(id) : null
    }

    /**
     * The tenant of the user running the task. 9.1 core passes that user in opts.user as its own domain object (not the
     * plugin model), and leaves Task.account empty, so the tenant id and master flag are read by property. Null when
     * they cannot be read: the task is then refused.
     */
    static Account runnerOf(Map opts) {
        Object u = opts?.user
        if (u instanceof User) {
            return (u as User).account
        }
        try {
            Object a = u?.account
            Long id = a?.id as Long
            id != null ? new Account(id: id, masterAccount: (a.masterAccount as Boolean) ?: false) : null
        } catch (Exception ignored) {
            null
        }
    }

    static String option(Task task, String code) {
        task?.taskOptions?.find { it.optionType?.code == code }?.value as String
    }

    TaskResult run(Task task, ComputeServer serverRef, Account runner = null) {
        List<String> out = []
        try {
            ComputeServer server = serverRef?.id ? morpheus.services.computeServer.get(serverRef.id) : null
            if (!server) {
                throw new IllegalArgumentException('This task runs against a VM (an instance or a server)')
            }
            Map settings = org.purehvm.PureFlashArrayPlugin.readSettings(morpheus, plugin)
            boolean freeze = option(task, OPT_FREEZE) in ['on', 'true']
            if (freeze) {
                ConsistentSnapshot.requireEnabled(settings)
            }
            String suffix = option(task, OPT_SUFFIX)?.trim() ?: null
            RecoveryOps.requireSuffix(suffix)   // before anything is frozen
            List<Long> dsIds = (server.volumes ?: []).collect { it.datastore?.id }.findAll().unique()
            List<Map> taken = []
            dsIds.each { Long dsId ->
                Datastore ds = morpheus.services.cloud.datastore.get(dsId)
                if (ds?.refType != 'ComputeServerGroup') {
                    out << "${ds?.name ?: dsId}: not a cluster datastore; skipped".toString()
                    return
                }
                PureClusterContext ctx = new PureClusterContext(morpheus, ds.refId)
                // the snapshot (and a freeze) acts on storage shared by every tenant on the cluster: only an operator
                // tenant of that cluster may run it, as for the cluster tab (the running user's tenant); unknown: refused
                Set<Long> extra = Tenancy.operatorTenantIds(settings)
                if (!(Tenancy.isOperatorTenant(runner, ctx.cluster, extra) || Tenancy.isOperatorTenant(task.account, ctx.cluster, extra))) {
                    throw new SecurityException("${ds.name}: the user running this task is not in a tenant that operates the storage of cluster ${ctx.cluster.name}")
                }
                Map arr = new RetireOps(morpheus, ctx).arrayVolume(ds)
                if (arr.error || !arr.volume) {
                    out << "${ds.name}: not a FlashArray datastore of the plugin (${arr.error ?: 'no array volume'}); skipped".toString()
                    return
                }
                ctx.withArray(arr.storageServerId as Long) { FlashArrayClient fa ->
                    Map r = freeze ? new ConsistentSnapshot(ctx, out).take(fa, arr.volume as String, suffix)
                            : [snapshot: new RecoveryOps(fa, ctx.runner, out).snapshot(arr.volume as String, suffix), consistent: false]
                    taken << [datastore: ds.name, snapshot: r.snapshot, applicationConsistent: freeze && r.consistent]
                    Audit.info(morpheus, null, ctx.cluster, 'workflow task took array snapshot', [server: server.name, datastore: ds.name, snapshot: r.snapshot, freeze: freeze])
                }
            }
            if (!taken) {
                throw new IllegalStateException("${server.name} has no disk on a FlashArray datastore of the plugin")
            }
            new TaskResult(success: true, data: [snapshots: taken], output: (out + taken.collect { "snapshot ${it.snapshot} of ${it.datastore}" }).join('\n'))
        } catch (Exception e) {
            out.add("FAILED: ${e.message}".toString())
            new TaskResult(success: false, error: e.message, output: out.join('\n'))
        }
    }
}
