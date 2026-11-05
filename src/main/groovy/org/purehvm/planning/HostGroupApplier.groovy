package org.purehvm.planning

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.FlashArrayException
import org.purehvm.planning.HostGroupPlanner.Action
import org.purehvm.planning.HostGroupPlanner.ArrayHost
import org.purehvm.planning.HostGroupPlanner.Node
import org.purehvm.planning.HostGroupPlanner.Plan

/**
 * Reads array state, plans, and (optionally) applies the host-group plan. Each create is idempotent:
 * an "already exists" answer is treated as success, as Pure's plugin does.
 */
class HostGroupApplier {
    private final FlashArrayClient fa
    private final HostGroupPlanner planner = new HostGroupPlanner()

    HostGroupApplier(FlashArrayClient fa) {
        this.fa = fa
    }

    Plan plan(String clusterName, List<Node> nodes, String preferredGroupName = null, boolean renameTaken = false) {
        List<Map> hosts = fa.listHosts()
        List<Map> privateConns = fa.listConnections([:]).findAll { it.host && !it.hostGroup }
        Map<String, Integer> privateCount = privateConns.countBy { it.host as String }
        List<ArrayHost> arrayHosts = hosts.collect { Map h ->
            new ArrayHost(h.name as String, (h.iqns ?: []) as List<String>, (h.host_group as Map)?.name as String, privateCount[h.name as String] ?: 0)
        }
        planner.plan(clusterName, nodes, arrayHosts, fa.listHostGroups()*.name as List<String>, preferredGroupName, renameTaken)
    }

    /** Applies a plan that is not blocked. Returns a log of what was done. */
    List<String> apply(Plan p, List<Node> nodes) {
        if (p.blocked) {
            throw new IllegalStateException("Plan is blocked: ${p.blockers.join('; ')}")
        }
        List<String> done = []
        Map<String, Node> nodeByName = nodes.collectEntries { [(it.name): it] }
        if (p.renameExisting) {
            String why = hvmGroupEvidence(p.nameTaken)
            if (why && !p.hvmGroupConfirmed) {
                throw new IllegalStateException("Host group ${p.nameTaken} looks like an HVM cluster's (${why}). If that cluster is still in use, " +
                        'on this or another Morpheus appliance, its link names the group and its next host would be added to the new one. Confirm the rename only for a retired cluster.')
            }
            // a rename keeps the group's hosts and volume connections; only its name changes
            fa.renameHostGroup(p.nameTaken, p.renameTo)
            done << "renamed host group ${p.nameTaken} to ${p.renameTo}".toString()
        }
        if (p.createHostGroup) {
            idempotent { fa.createHostGroup(p.hostGroup) }
            done << "created host group ${p.hostGroup}".toString()
        }
        p.hosts.each { HostGroupPlanner.HostStep s ->
            Node n = nodeByName[s.node]
            switch (s.action) {
                case Action.CREATE:
                    idempotent { fa.createHost(s.arrayHost, n.iqns) }
                    idempotent { fa.addHostsToHostGroup(p.hostGroup, [s.arrayHost]) }
                    done << "created host ${s.arrayHost} and added it to ${p.hostGroup}".toString()
                    break
                case Action.ADD_TO_GROUP:
                    idempotent { fa.addHostsToHostGroup(p.hostGroup, [s.arrayHost]) }
                    done << "added ${s.arrayHost} to ${p.hostGroup}".toString()
                    break
                case Action.REUSE:
                    Map existing = addMissingIqns(s.arrayHost, n.iqns)
                    if (!(existing?.host_group as Map)?.name) {
                        idempotent { fa.addHostsToHostGroup(p.hostGroup, [s.arrayHost]) }
                    }
                    done << "added IQN ${n.iqns.join(', ')} to ${s.arrayHost} (rebuilt host) in ${p.hostGroup}".toString()
                    break
                case Action.ADD_IQN:
                    addMissingIqns(s.arrayHost, n.iqns)
                    done << "added IQN(s) to ${s.arrayHost}".toString()
                    break
                default:
                    break
            }
        }
        done
    }

    /**
     * Why a host group may belong to an HVM cluster (possibly on another Morpheus appliance, whose link this one cannot
     * see), or null: a member host with an HPE-generated (HVM) initiator name, or a connected volume the plugin tagged.
     * An old VMware cluster's group (ESXi IQNs, untagged VMFS volumes) passes.
     */
    String hvmGroupEvidence(String group) {
        Set<String> members = fa.listHostGroupMembers([group]).findAll { it.group == group }*.member as Set<String>
        List<String> hvmHosts = fa.listHosts().findAll { members.contains(it.name) && ((it.iqns ?: []) as List<String>).any { String q -> q?.toLowerCase()?.contains('.com.hpe:') } }*.name as List<String>
        if (hvmHosts) {
            return "member host(s) with an HVM initiator name: ${hvmHosts.join(', ')}"
        }
        List<String> vols = fa.listConnections([host_group_names: group]).findAll { it.hostGroup == group }*.volume.unique() as List<String>
        List<String> tagged = vols ? fa.tagsByVolume().findAll { k, v -> vols.contains(k) && v.role }*.key as List<String> : []
        tagged ? "connected volume(s) managed by the plugin: ${tagged.join(', ')}" : null
    }

    /** Adds the node's IQNs the array host lacks (compared as the planner compares them); returns the host as it was. */
    private Map addMissingIqns(String arrayHost, List<String> iqns) {
        Map current = fa.listHosts().find { it.name == arrayHost }
        Set<String> have = ((current?.iqns ?: []) as List<String>).collect { HostGroupPlanner.normalizeIqn(it) } as Set<String>
        List<String> missing = iqns.findAll { !have.contains(HostGroupPlanner.normalizeIqn(it)) }
        if (missing) {
            fa.addIqnsToHost(arrayHost, missing)
        }
        current
    }

    private static void idempotent(Closure c) {
        try {
            c.call()
        } catch (FlashArrayException e) {
            if (!e.alreadyExists) {
                throw e
            }
        }
    }
}
