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

    Plan plan(String clusterName, List<Node> nodes, String preferredGroupName = null) {
        List<Map> hosts = fa.listHosts()
        List<Map> privateConns = fa.listConnections([:]).findAll { it.host && !it.hostGroup }
        Map<String, Integer> privateCount = privateConns.countBy { it.host as String }
        List<ArrayHost> arrayHosts = hosts.collect { Map h ->
            new ArrayHost(h.name as String, (h.iqns ?: []) as List<String>, (h.host_group as Map)?.name as String, privateCount[h.name as String] ?: 0)
        }
        planner.plan(clusterName, nodes, arrayHosts, fa.listHostGroups()*.name as List<String>, preferredGroupName)
    }

    /** Applies a plan that is not blocked. Returns a log of what was done. */
    List<String> apply(Plan p, List<Node> nodes) {
        if (p.blocked) {
            throw new IllegalStateException("Plan is blocked: ${p.blockers.join('; ')}")
        }
        List<String> done = []
        Map<String, Node> nodeByName = nodes.collectEntries { [(it.name): it] }
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
