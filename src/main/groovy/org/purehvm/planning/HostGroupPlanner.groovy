package org.purehvm.planning

import org.purehvm.api.PureNames
import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Works out what has to change on a FlashArray so that one HVM cluster maps to one host group containing
 * one array host per HVM node (matched by iSCSI IQN). Pure logic: no I/O, so the plan can be previewed in
 * the UI before anything is applied, the way Pure's "Add/Update Host Groups" wizard does.
 *
 * Rules (Pure's vSphere plugin, adapted for Linux nodes):
 *  - An array host matches a node if any of its IQNs is one of the node's IQNs.
 *  - The cluster's host group is the one already containing the most matched hosts; if matched hosts sit in
 *    two different groups the plan is blocked (an operator must decide).
 *  - Unmatched node -> create host named after its short hostname (suffix -iscsi, -iscsi-1 on collision).
 *  - Matched host with no group -> add it to the group. Its private volume connections are listed as a
 *    warning: Purity rejects the add if a private LUN ID collides with a host-group LUN ID.
 *  - Matched host in another group -> conflict, never moved automatically.
 *  - Array hosts in the group that match no node are reported, not removed (could be a node being rebuilt).
 */
@CompileStatic
class HostGroupPlanner {

    @Canonical
    static class Node {
        String name          // Morpheus server name / hostname
        List<String> iqns    // what the host really logs in with
        List<String> previousIqns = []   // a stale IQN Morpheus still records for it (host rebuilt), evidence for REUSE
    }

    @Canonical
    static class ArrayHost {
        String name
        List<String> iqns
        String hostGroup     // null if standalone
        int privateConnections
    }

    /**
     * REUSE: the node's IQN is on no array host, but an array host with the node's own name exists whose IQNs belong to
     * no node of this cluster - the same machine rebuilt with a new initiator name. Add the new IQN to it (and put it in
     * the group) instead of creating a "-iscsi" duplicate.
     */
    static enum Action { OK, CREATE, ADD_TO_GROUP, ADD_IQN, REUSE, CONFLICT }

    @Canonical
    static class HostStep {
        String node
        String arrayHost
        Action action
        String detail
    }

    @Canonical
    static class Plan {
        String hostGroup
        boolean createHostGroup
        List<HostStep> hosts = []
        List<String> warnings = []
        List<String> blockers = []
        List<String> strayHosts = []

        boolean isBlocked() { !blockers.isEmpty() }

        boolean isNoop() { !createHostGroup && hosts.every { it.action == Action.OK } }
    }

    static String normalizeIqn(String iqn) {
        (iqn ?: '').trim().toLowerCase()
    }

    /** The array host name a node gets: its short hostname as a valid Purity name. */
    static String arrayHostName(String node) {
        PureNames.sanitize(PureNames.shortHostname(node))
    }

    Plan plan(String clusterName, List<Node> nodes, List<ArrayHost> arrayHosts, Collection<String> existingGroups, String preferredGroupName = null) {
        Plan p = new Plan()
        if (!nodes) {
            p.blockers << 'The cluster has no hosts'
            return p
        }

        Map<String, ArrayHost> byIqn = [:]
        arrayHosts.each { ArrayHost h -> h.iqns.each { String q -> byIqn[normalizeIqn(q)] = h } }

        Map<Node, ArrayHost> matched = [:]
        nodes.each { Node n ->
            if (!n.iqns) {
                p.blockers << "${n.name}: no iSCSI initiator name found (is open-iscsi installed?)".toString()
                return
            }
            Set<ArrayHost> hits = n.iqns.collect { byIqn[normalizeIqn(it)] }.findAll { it != null } as Set<ArrayHost>
            if (hits.size() > 1) {
                p.blockers << "${n.name}: its IQNs are registered on several array hosts (${hits*.name.join(', ')})".toString()
            } else if (hits) {
                matched[n] = hits.first()
            }
        }

        // Same machine, new IQN (rebuilt): an array host named after the node, whose IQNs are all evidently this
        // node's own earlier identity - the stale IQN Morpheus still records for it, or an HPE-generated
        // iqn.<date>.com.hpe:<hostname>:<n>. A same-named host with a foreign IQN (an ESXi host during a VMware->HVM
        // migration: iqn.1998-01.com.vmware:esx-hvm01) is never touched.
        Set<String> allNodeIqns = [] as Set<String>
        nodes.each { Node n -> (n.iqns ?: [] as List<String>).each { String q -> allNodeIqns << normalizeIqn(q) } }
        Map<Node, ArrayHost> renamedIqn = [:]
        nodes.each { Node n ->
            if (!n.iqns || matched.containsKey(n)) {
                return
            }
            String shortName = PureNames.shortHostname(n.name)
            String own = arrayHostName(n.name)
            Set<String> previous = (n.previousIqns ?: [] as List<String>).collect { String q -> normalizeIqn(q) } as Set<String>
            String hpeOwn = /iqn\.\d{4}-\d{2}\.com\.hpe:${java.util.regex.Pattern.quote(shortName.toLowerCase())}:.+/
            ArrayHost h = arrayHosts.find { it.name.equalsIgnoreCase(own) }
            if (h && h.iqns && !h.iqns.any { allNodeIqns.contains(normalizeIqn(it)) } &&
                    h.iqns.every { String q -> previous.contains(normalizeIqn(q)) || normalizeIqn(q) ==~ hpeOwn }) {
                renamedIqn[n] = h
            }
        }

        // Choose the host group. A rebuilt node's old array host votes only for this cluster's own group (the
        // recorded one, or the one named after the cluster) - never pulling the node into another cluster's group.
        Set<String> ownGroups = ([preferredGroupName, PureNames.sanitize(clusterName)].findAll() as List<String>).collect { String g -> g.toLowerCase() } as Set<String>
        Map<String, Integer> groupVotes = [:]
        (matched.values() + renamedIqn.values().findAll { ArrayHost h -> h.hostGroup && ownGroups.contains(h.hostGroup.toLowerCase()) })
                .findAll { it.hostGroup }.each { ArrayHost h -> groupVotes[h.hostGroup] = (groupVotes[h.hostGroup] ?: 0) + 1 }
        if (groupVotes.size() > 1) {
            p.blockers << "Cluster hosts are already split across host groups: ${groupVotes.keySet().sort().join(', ')}".toString()
        }
        if (groupVotes) {
            p.hostGroup = groupVotes.max { it.value }.key
        } else {
            String want = PureNames.sanitize(preferredGroupName ?: clusterName)
            p.hostGroup = PureNames.nonColliding(want, existingGroups)
            p.createHostGroup = true
            if (p.hostGroup != want) {
                p.warnings << "Host group name ${want} is taken by another group; will create ${p.hostGroup}".toString()
            }
        }

        Set<String> takenHostNames = arrayHosts*.name as Set<String>
        nodes.each { Node n ->
            if (!n.iqns) {
                return
            }
            ArrayHost h = matched[n]
            ArrayHost old = renamedIqn[n]
            if (h == null && old != null) {
                if (old.hostGroup && old.hostGroup != p.hostGroup) {
                    p.hosts << new HostStep(n.name, old.name, Action.CONFLICT,
                            "array host ${old.name} (IQN ${old.iqns.join(', ')}) is in host group ${old.hostGroup}; this host now uses ${n.iqns.join(', ')} - rebuilt? fix it on the array".toString())
                } else {
                    p.hosts << new HostStep(n.name, old.name, Action.REUSE,
                            "add IQN ${n.iqns.join(', ')} to ${old.name} (it has ${old.iqns.join(', ')}, which no host of this cluster uses)${old.hostGroup ? '' : ' and add it to ' + p.hostGroup}".toString())
                    p.warnings << "${old.name} keeps its old IQN ${old.iqns.join(', ')} after the update; remove it on the array once nothing uses it".toString()
                }
                return
            }
            if (h == null) {
                String name = PureNames.nonColliding(arrayHostName(n.name), takenHostNames)
                takenHostNames << name
                p.hosts << new HostStep(n.name, name, Action.CREATE, "create host with ${n.iqns.join(', ')}")
                return
            }
            List<String> missing = n.iqns.findAll { String q -> !h.iqns.collect { normalizeIqn(it) }.contains(normalizeIqn(q)) }
            if (h.hostGroup && h.hostGroup != p.hostGroup) {
                p.hosts << new HostStep(n.name, h.name, Action.CONFLICT, "array host ${h.name} is in host group ${h.hostGroup}")
            } else if (!h.hostGroup) {
                p.hosts << new HostStep(n.name, h.name, Action.ADD_TO_GROUP, "add ${h.name} to ${p.hostGroup}")
                if (h.privateConnections > 0) {
                    p.warnings << "${h.name} has ${h.privateConnections} private volume connection(s); Purity refuses the add if a LUN ID collides".toString()
                }
            } else if (missing) {
                p.hosts << new HostStep(n.name, h.name, Action.ADD_IQN, "add IQN(s) ${missing.join(', ')}")
            } else {
                p.hosts << new HostStep(n.name, h.name, Action.OK, 'already configured')
            }
        }
        if (p.hosts.any { it.action == Action.CONFLICT }) {
            p.blockers << 'Some hosts belong to a different host group; move them on the array first'
        }

        Set<String> planned = p.hosts*.arrayHost as Set<String>
        p.strayHosts = arrayHosts.findAll { it.hostGroup == p.hostGroup && !planned.contains(it.name) }*.name.sort()
        if (p.strayHosts) {
            p.warnings << "Host group ${p.hostGroup} also contains ${p.strayHosts.join(', ')}, which are not in this cluster (left untouched)".toString()
        }
        p
    }
}
