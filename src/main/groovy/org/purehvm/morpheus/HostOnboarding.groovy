package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Datastore
import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import org.purehvm.ops.Adoption
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.planning.HostGroupApplier
import org.purehvm.planning.HostGroupPlanner
import groovy.util.logging.Slf4j

/**
 * Bringing a host that was added to an HVM cluster onto the FlashArray and the cluster's GFS2 datastores:
 *   1. add it to the cluster's host group (create the array host by IQN if needed) - array side, one host only
 *   2. rescan iSCSI/multipath on it so the cluster's LUNs appear
 *   3. ask Morpheus to refresh each GFS2 datastore (Morpheus owns GFS2 mounting) and re-check
 *   4. if still not mounted: mount exactly like a host that already has it (same path, options, libvirt pool),
 *      guarded by the corosync/DLM membership checks in HostScripts.mountGfs2
 * Used by the cluster tab (buttons) and by the ADD_WORKER event handler.
 */
@Slf4j
class HostOnboarding {
    final MorpheusContext morpheus
    final PureClusterContext ctx
    final List<String> progress

    HostOnboarding(MorpheusContext morpheus, PureClusterContext ctx, List<String> progress = []) {
        this.morpheus = morpheus
        this.ctx = ctx
        this.progress = progress
    }

    private void step(String s) {
        progress << s
        log.info("[${ctx.cluster.name}] ${s}")
    }

    /** The cluster's Morpheus datastores as adoption/onboarding references. */
    static List<Adoption.DatastoreRef> clusterDatastores(PureClusterContext ctx) {
        ctx.datastores.collect { Datastore d ->
            new Adoption.DatastoreRef(id: d.id, name: d.name, typeCode: d.datastoreType?.code, active: d.active,
                    blockDevice: d.getConfigProperty('blockDevice') as String, mountPath: d.externalPath)
        }
    }

    /** The cluster's GFS2 datastores that live on a FlashArray: [id, name, wwid, mountPath]. */
    List<Map> pureGfs2() {
        clusterDatastores(ctx).findAll { Adoption.isGfs2(it) && PureNames.serialFromWwid(it.blockDevice) }.collect {
            [id: it.id, name: it.name, wwid: PureNames.wwid(PureNames.serialFromWwid(it.blockDevice)), mountPath: it.mountPath]
        }
    }

    /** Per host: [ds name, wwid, mountPath, present, mountedAt, state]. state: mounted | other-path | visible | missing */
    Map<String, List<Map>> storage(List<String> hosts, List<Map> datastores) {
        if (!datastores) {
            return hosts.collectEntries { [(it): []] }
        }
        Map<String, HostRunner.Result> res = ctx.onAllHosts(HostScripts.storageState(datastores*.wwid as List<String>), hosts)
        res.collectEntries { String h, HostRunner.Result r ->
            Map<String, List<String>> byWwid = r.all('STORAGE').collectEntries { String l -> List<String> p = l.split(/\|/, -1) as List<String>; [(p[0]): p] }
            [(h): datastores.collect { Map d ->
                List<String> p = byWwid[d.wwid as String]
                boolean present = p && p[1] == '1'
                String at = p ? p[2] : null
                // a record without externalPath cannot disagree with the host's mount point
                String state = !r.ok ? 'unknown' : (at ? (!d.mountPath || at == d.mountPath ? 'mounted' : 'other-path') : (present ? 'visible' : 'missing'))
                [name: d.name, wwid: d.wwid, mountPath: d.mountPath, present: present, mountedAt: at, state: state]
            }]
        } as Map<String, List<Map>>
    }

    /** Step 1 on one array: put the host into that array's host group for this cluster (only this host's step). */
    void addToArray(Map link, String host, List<HostGroupPlanner.Node> nodes) {
        Long sid = link.storageServerId as Long
        String arrayName = ctx.storageServer(sid).name
        ctx.withArray(sid) { FlashArrayClient fa ->
            HostGroupApplier ap = new HostGroupApplier(fa)
            HostGroupPlanner.Plan plan = ap.plan(ctx.cluster.name, nodes, link.hostGroup as String)
            HostGroupPlanner.HostStep s = plan.hosts.find { it.node == host }
            if (!s) {
                throw new IllegalStateException("${arrayName}: ${plan.blockers.join('; ') ?: 'no plan step (no iSCSI initiator name?)'}")
            }
            if (s.action == HostGroupPlanner.Action.CONFLICT) {
                throw new IllegalStateException("${arrayName}: ${s.detail}; move it on the array first")
            }
            String other = ctx.clusterUsing(sid, plan.hostGroup)
            if (other) {
                throw new IllegalStateException("${arrayName}: host group ${plan.hostGroup} belongs to cluster ${other}; refusing to add ${host}")
            }
            if (s.action == HostGroupPlanner.Action.OK) {
                step("${arrayName}: ${host} already in host group ${plan.hostGroup} as ${s.arrayHost}")
            } else {
                // only this host's step: other hosts' issues must not block adding this one
                HostGroupPlanner.Plan one = new HostGroupPlanner.Plan(hostGroup: plan.hostGroup, createHostGroup: plan.createHostGroup, hosts: [s])
                ap.apply(one, nodes).each { step("${arrayName}: ${it}".toString()) }
            }
            if (link.hostGroup != plan.hostGroup) {
                ctx.setLink(sid, plan.hostGroup)
            }
        }
    }

    /**
     * Steps 1-3 for one host: every linked array's host group (or only {@code onlyStorageServerId}), then one rescan
     * and the storage check. Returns its storage state afterwards.
     */
    List<Map> add(String host, Long onlyStorageServerId = null) {
        ctx.requireHost(host)
        List<Map> links = ctx.links.findAll { !onlyStorageServerId || it.storageServerId == onlyStorageServerId }
        if (!links) {
            throw new IllegalStateException("Cluster ${ctx.cluster.name} is not linked to ${onlyStorageServerId ? 'that array' : 'any FlashArray'}")
        }
        List<HostGroupPlanner.Node> nodes = ctx.nodes
        List<String> failures = []
        links.each { Map l ->
            try {
                addToArray(l, host, nodes)
            } catch (Exception e) {
                failures << e.message
                step("FAILED ${e.message}")
            }
        }
        if (failures.size() == links.size()) {
            throw new IllegalStateException(failures.join('; '))
        }

        HostRunner.Result rs = ctx.runner.run(host, HostScripts.rescanAll())
        step("${host}: rescanned - ${rs.kv.SESSIONS ?: 0} FlashArray iSCSI session(s), ${rs.kv.PURE_MAPS ?: 0} FlashArray LUN(s) visible" +
                (rs.kv.SESSIONS == '0' ? ' (no sessions: run the compliance check and Remediate this host)' : ''))

        List<Map> ds = pureGfs2()
        List<Map> st = storage([host], ds)[host]
        if (st.any { it.state == 'visible' }) {
            ds.findAll { d -> st.find { it.wwid == d.wwid }?.state == 'visible' }.each { Map d ->
                try {
                    Datastore rec = morpheus.services.cloud.datastore.get(d.id as Long)
                    MorpheusSession.run(morpheus) { morpheus.services.storage.datastoreType.refreshDatastore(rec) }
                    step("asked Morpheus to refresh datastore ${d.name}")
                } catch (Exception e) {
                    step("Morpheus refresh of ${d.name} failed: ${e.message}")
                }
            }
            sleep(10000)
            st = storage([host], ds)[host]
        }
        st.each { step("${host}: ${it.name} - ${describe(it)}") }
        st
    }

    /** Step 4: mount every visible-but-unmounted Pure GFS2 datastore on the host, copying a host that has it. */
    List<Map> mount(String host) {
        List<Map> ds = pureGfs2()
        Map<String, List<Map>> all = storage(ctx.hostNames, ds)
        List<Map> mine = all[host] ?: []
        mine.findAll { it.state == 'visible' }.each { Map d ->
            String ref = all.find { h, rows -> h != host && rows.find { it.wwid == d.wwid }?.state == 'mounted' }?.key
            if (!ref) {
                step("${d.name}: no host has it mounted to copy from; skipped")
                return
            }
            HostRunner.Result rr = ctx.runner.run(ref, HostScripts.mountReference(d.wwid as String, d.mountPath as String))
            if (!rr.ok) {
                step("${d.name}: could not read the mount from ${ref}: ${rr.error}")
                return
            }
            HostRunner.Result m = ctx.runner.run(host, HostScripts.mountGfs2(d.wwid as String, d.mountPath as String, rr.kv.OPTS, rr.kv.POOL, rr.kv.POOLXML))
            step(m.ok ? "${d.name}: mounted at ${m.kv.MOUNTED} on ${host} (pool ${m.kv.POOL ?: rr.kv.POOL ?: 'n/a'}, copied from ${ref})".toString()
                      : "${d.name}: mount on ${host} refused - ${m.error}".toString())
        }
        mine.findAll { it.state == 'missing' }.each { step("${it.name}: LUN not visible on ${host}; add the host to the host group and rescan first") }
        storage([host], ds)[host]
    }

    static String describe(Map s) {
        switch (s.state) {
            case 'mounted': return "mounted at ${s.mountedAt}"
            case 'other-path': return "mounted at ${s.mountedAt} (other hosts use ${s.mountPath})"
            case 'visible': return 'LUN visible, not mounted'
            case 'missing': return 'LUN not visible'
            default: return 'unknown (host did not answer)'
        }
    }
}
