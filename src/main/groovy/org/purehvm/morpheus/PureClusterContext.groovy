package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.TaskResult
import org.purehvm.api.FlashArrayClient
import org.purehvm.ops.DatastoreOps
import org.purehvm.ops.HostRunner
import org.purehvm.ops.ScriptWrapper
import org.purehvm.planning.HostGroupPlanner

import java.util.concurrent.TimeUnit

/**
 * Everything an operation needs about one HVM cluster: its hypervisor hosts, a way to run scripts on them
 * (through the Morpheus agent / edge worker, never direct SSH), and the FlashArrays it is linked to.
 *
 * A cluster can be linked to several arrays and an array to several clusters. Links live in the cluster's config
 * map as pureLinks = [[storageServerId, hostGroup], ...]; the 0.1-0.3 single link (pureStorageServerId +
 * pureHostGroup) is read as a one-element list and rewritten in the new form on the next save.
 */
class PureClusterContext {
    static final String CFG_LINKS = 'pureLinks'
    static final String CFG_STORAGE_SERVER = 'pureStorageServerId'   // legacy (single link)
    static final String CFG_HOST_GROUP = 'pureHostGroup'             // legacy (single link)
    static final long SCRIPT_TIMEOUT_SEC = 900

    final MorpheusContext morpheus
    final ComputeServerGroup cluster

    PureClusterContext(MorpheusContext morpheus, Long clusterId) {
        this.morpheus = morpheus
        this.cluster = morpheus.services.cluster.get(clusterId)
        if (!cluster) {
            throw new IllegalArgumentException("No cluster ${clusterId}")
        }
    }

    // ------------------------------------------------------------------ hosts

    /** Hypervisor nodes of the cluster (VMs have a parentServer; hosts do not). */
    List<ComputeServer> getHosts() {
        morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', cluster.id))
                .findAll { it.parentServer == null }
                .sort { it.name }
    }

    List<String> getHostNames() {
        hosts*.name
    }

    HostRunner getRunner() {
        Map<String, ComputeServer> byName = hosts.collectEntries { [(it.name): it] }
        return { String host, String script ->
            ComputeServer s = byName[host]
            if (!s) {
                return new HostRunner.Result(127, "ERROR=unknown host ${host}")
            }
            TaskResult tr = morpheus.executeCommandOnServer(s, ScriptWrapper.wrap(script))
                    .timeout(SCRIPT_TIMEOUT_SEC, TimeUnit.SECONDS).blockingGet()
            String out = tr.output ?: (tr.data instanceof String ? tr.data : '') ?: ''
            Integer code = tr.exitCode?.isInteger() ? tr.exitCode as Integer : (tr.success ? 0 : 1)
            new HostRunner.Result(code, out + (tr.error ? "\nERROR=${tr.error}" : ''))
        } as HostRunner
    }

    /**
     * Per node: the IQN the host actually logs in with (/etc/iscsi/initiatorname.iscsi) and the one in the Morpheus
     * server record. They can differ: after a host is rebuilt, Morpheus can keep the old build's IQN, and an
     * array host made from the record never saw its LUNs.
     */
    List<Map> getIqnReport() {
        HostRunner r = runner
        hosts.collect { ComputeServer s -> iqnRow(s, r) }
    }

    private static Map iqnRow(ComputeServer s, HostRunner r) {
        String actual = null
        try {
            actual = r.run(s.name, "sed -n 's/^InitiatorName=//p' /etc/iscsi/initiatorname.iscsi").output?.readLines()?.find { it.startsWith('iqn.') }?.trim()
        } catch (Exception e) {
            // host unreachable: fall back to the record below
        }
        String record = s.getiScsiInitiatorName()?.trim() ?: null
        [node: s.name, actual: actual, record: record, used: actual ?: record,
         mismatch: actual && record && !actual.equalsIgnoreCase(record)]
    }

    /**
     * The array host object of one node, found by the IQN the node really uses (only that node is asked, so this is
     * cheap enough to call per operation).
     */
    String arrayHostFor(FlashArrayClient fa, String node) {
        ComputeServer s = hosts.find { it.name == node }
        String iqn = s ? iqnRow(s, runner).used?.toLowerCase() : null
        Map h = iqn ? fa.listHosts().find { Map ah -> ((ah.iqns ?: []) as List<String>)*.toLowerCase().contains(iqn) } : null
        if (!h) {
            throw new IllegalStateException("${node} has no host object on the array (run the host group step)")
        }
        h.name
    }

    void requireHost(String host) {
        if (!hostNames.contains(host)) {
            throw new IllegalArgumentException("${host} is not in cluster ${cluster.name}")
        }
    }

    /** Runs a script on the given hosts (default: every host of the cluster) in parallel; host -> result. */
    Map<String, HostRunner.Result> onAllHosts(String script, List<String> on = hostNames) {
        DatastoreOps.onAllHosts(runner, on, script)
    }

    // ------------------------------------------------------------------ datastores and VMs

    /** Every Morpheus datastore of the cluster. */
    List<Datastore> getDatastores() {
        morpheus.services.cloud.datastore.list(new DataQuery().withFilter('refType', 'ComputeServerGroup').withFilter('refId', cluster.id))
    }

    /** A datastore by id, refused unless it belongs to this cluster. */
    Datastore datastore(Long id) {
        Datastore ds = id ? morpheus.services.cloud.datastore.get(id) : null
        if (!ds || ds.refType != 'ComputeServerGroup' || ds.refId != cluster.id) {
            throw new IllegalArgumentException("Datastore ${id} is not a datastore of cluster ${cluster.name}")
        }
        ds
    }

    /** VMs running on (or assigned to) the cluster's hosts. */
    List<ComputeServer> getVms() {
        Set<Long> hostIds = hosts*.id as Set<Long>
        morpheus.services.computeServer.list(new DataQuery().withFilter('cloud.id', cluster.cloud.id))
                .findAll { it.parentServer?.id && hostIds.contains(it.parentServer.id) }
    }

    /**
     * Writes the IQN each host really uses into its Morpheus server record where the record is empty or stale.
     * Besides keeping Morpheus truthful, this removes the risk of Morpheus ever pushing a stale IQN back to the host,
     * which would cut it off from every LUN the array masks to its real IQN. Returns one line per record changed.
     */
    List<String> syncIqnRecords(List<Map> report = null) {
        List<String> changed = []
        (report ?: iqnReport).findAll { it.actual && (it.mismatch || !it.record) }.each { Map q ->
            ComputeServer listed = hosts.find { it.name == q.node }
            ComputeServer s = listed ? morpheus.services.computeServer.get(listed.id) : null
            if (!s) {
                return
            }
            s.setiScsiInitiatorName(q.actual as String)
            morpheus.services.computeServer.save(s)
            changed << "${q.node}: Morpheus IQN record ${q.record ?: '(empty)'} -> ${q.actual}".toString()
        }
        changed
    }

    /** IQN per node: what the host really uses (the array masks LUNs by it); the Morpheus record only if the host can't be read. */
    List<HostGroupPlanner.Node> getNodes() {
        nodesOf(iqnReport)
    }

    static List<HostGroupPlanner.Node> nodesOf(List<Map> iqnReport) {
        iqnReport.collect { Map q ->
            new HostGroupPlanner.Node(q.node as String, q.used ? [q.used as String] : [], q.mismatch ? [q.record as String] : [])
        }
    }

    // ------------------------------------------------------------------ array links

    static List<Map> linksOf(ComputeServerGroup c) {
        Object v = c.getConfigProperty(CFG_LINKS)
        if (v instanceof List) {
            return (v as List<Map>).findAll { it?.storageServerId }.collect { [storageServerId: it.storageServerId as Long, hostGroup: it.hostGroup as String] }
        }
        Object legacy = c.getConfigProperty(CFG_STORAGE_SERVER)
        legacy ? [[storageServerId: legacy as Long, hostGroup: c.getConfigProperty(CFG_HOST_GROUP) as String]] : []
    }

    List<Map> getLinks() {
        linksOf(cluster)
    }

    Map link(Long storageServerId) {
        links.find { it.storageServerId == storageServerId }
    }

    private void saveLinks(List<Map> links) {
        cluster.setConfigProperty(CFG_LINKS, links.collect { [storageServerId: it.storageServerId, hostGroup: it.hostGroup] })
        cluster.setConfigProperty(CFG_STORAGE_SERVER, null)
        cluster.setConfigProperty(CFG_HOST_GROUP, null)
        morpheus.services.cluster.save(cluster)
    }

    /** Adds the link or updates its host group. Refuses a host group another cluster already uses on that array. */
    void setLink(Long storageServerId, String hostGroup) {
        if (hostGroup) {
            String other = clusterUsing(storageServerId, hostGroup)
            if (other) {
                throw new IllegalStateException("Host group ${hostGroup} on this array is already linked to cluster ${other}. " +
                        'Two clusters sharing a host group would both see its GFS2 LUNs, and mounting one GFS2 filesystem from two ' +
                        'corosync clusters corrupts it.')
            }
        }
        List<Map> ls = links.findAll { it.storageServerId != storageServerId }
        ls << [storageServerId: storageServerId, hostGroup: hostGroup ?: link(storageServerId)?.hostGroup]
        saveLinks(ls)
    }

    /** Forget the recorded host group of a link (the planner then picks it again from the array). */
    void clearHostGroup(Long storageServerId) {
        saveLinks(links.collect { it.storageServerId == storageServerId ? [storageServerId: it.storageServerId, hostGroup: null] : it })
    }

    void unlink(Long storageServerId) {
        saveLinks(links.findAll { it.storageServerId != storageServerId })
    }

    /** Name of ANOTHER cluster linked to the same array + host group, or null. */
    String clusterUsing(Long storageServerId, String hostGroup) {
        if (!hostGroup) {
            return null
        }
        morpheus.services.cluster.list(new DataQuery()).find { ComputeServerGroup c ->
            c.id != cluster.id && linksOf(c).any { it.storageServerId == storageServerId && it.hostGroup?.equalsIgnoreCase(hostGroup) }
        }?.name
    }

    /**
     * The link an operation applies to: the requested array (must be linked), or the only linked array when no array
     * was named.
     */
    Map resolve(Object requested) {
        List<Map> ls = links
        if (!ls) {
            throw new IllegalStateException("Cluster ${cluster.name} is not linked to a FlashArray yet")
        }
        if (requested?.toString()?.isLong()) {
            Map l = ls.find { it.storageServerId == requested as Long }
            if (!l) {
                throw new IllegalArgumentException("Array ${requested} is not linked to cluster ${cluster.name}")
            }
            return l
        }
        if (ls.size() > 1) {
            throw new IllegalArgumentException('This cluster is linked to several arrays: choose one (storageServerId)')
        }
        ls[0]
    }

    StorageServer storageServer(Long storageServerId) {
        StorageServer s = morpheus.services.storageServer.get(storageServerId)
        if (!s) {
            throw new IllegalStateException("Storage server ${storageServerId} no longer exists")
        }
        s
    }

    FlashArrayClient client(Long storageServerId) {
        PureStorageProvider.client(storageServer(storageServerId))
    }

    /** Opens a client to the array, runs {@code c} with it and always logs out. */
    def <T> T withArray(Long storageServerId, Closure<T> c) {
        FlashArrayClient fa = client(storageServerId)
        try {
            c.call(fa)
        } finally {
            fa.close()
        }
    }
}
