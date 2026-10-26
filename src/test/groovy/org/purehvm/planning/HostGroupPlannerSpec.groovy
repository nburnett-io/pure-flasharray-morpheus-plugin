package org.purehvm.planning

import org.purehvm.planning.HostGroupPlanner.Action
import org.purehvm.planning.HostGroupPlanner.ArrayHost
import org.purehvm.planning.HostGroupPlanner.Node
import spock.lang.Specification

class HostGroupPlannerSpec extends Specification {
    def planner = new HostGroupPlanner()
    def nodes = [
            new Node('hvm01.example.invalid', ['iqn.2024-12.com.hpe:hvm01:aaa']),
            new Node('hvm02.example.invalid', ['iqn.2024-12.com.hpe:hvm02:bbb']),
            new Node('hvm03.example.invalid', ['iqn.2024-12.com.hpe:hvm03:ccc']),
    ]

    def 'empty array: create host group named after the cluster and one host per node'() {
        when:
        def p = planner.plan('HVM Cluster A', nodes, [], [])

        then:
        p.hostGroup == 'HVM-Cluster-A'
        p.createHostGroup
        p.hosts*.action == [Action.CREATE] * 3
        p.hosts*.arrayHost == ['hvm01', 'hvm02', 'hvm03']
        !p.blocked
    }

    def 'fully configured cluster is a no-op'() {
        given:
        def hosts = nodes.collect { new ArrayHost(it.name.tokenize('.')[0], it.iqns, 'hvm-a', 0) }

        when:
        def p = planner.plan('hvm-a', nodes, hosts, ['hvm-a'])

        then:
        p.noop
        p.hostGroup == 'hvm-a'
    }

    def 'IQN match is case-insensitive and reuses the existing host name'() {
        given:
        def hosts = [new ArrayHost('legacy-name', ['IQN.2024-12.COM.HPE:HVM01:AAA'], null, 0)]

        when:
        def p = planner.plan('c1', nodes, hosts, [])

        then:
        p.hosts.find { it.node.startsWith('hvm01') }.with { arrayHost == 'legacy-name' && action == Action.ADD_TO_GROUP }
    }

    def 'existing group with most matched hosts is chosen; missing node gets created and added'() {
        given:
        def hosts = [
                new ArrayHost('hvm01', nodes[0].iqns, 'prod-hg', 0),
                new ArrayHost('hvm02', nodes[1].iqns, 'prod-hg', 0),
        ]

        when:
        def p = planner.plan('different-name', nodes, hosts, ['prod-hg'])

        then:
        p.hostGroup == 'prod-hg'
        !p.createHostGroup
        p.hosts.find { it.node.startsWith('hvm03') }.action == Action.CREATE
    }

    def 'hosts split across two groups block the plan'() {
        given:
        def hosts = [
                new ArrayHost('hvm01', nodes[0].iqns, 'hg-a', 0),
                new ArrayHost('hvm02', nodes[1].iqns, 'hg-b', 0),
        ]

        when:
        def p = planner.plan('c1', nodes, hosts, ['hg-a', 'hg-b'])

        then:
        p.blocked
        p.hosts.any { it.action == Action.CONFLICT }
    }

    def 'host name collision with an unrelated host gets the -iscsi suffix'() {
        given:
        def hosts = [new ArrayHost('hvm01', ['iqn.1998-01.com.vmware:esx-hvm01'], 'esx-cluster', 0)]

        when:
        def p = planner.plan('c1', nodes, hosts, ['esx-cluster'])

        then:
        p.hosts.find { it.node.startsWith('hvm01') }.arrayHost == 'hvm01-iscsi'
    }

    def 'private connections on a standalone host produce a LUN-collision warning'() {
        given:
        def hosts = [new ArrayHost('hvm01', nodes[0].iqns, null, 2)]

        when:
        def p = planner.plan('c1', nodes, hosts, [])

        then:
        p.warnings.any { it.contains('private volume connection') }
    }

    def 'node without an IQN blocks'() {
        expect:
        planner.plan('c1', [new Node('hvm09', [])], [], []).blocked
    }

    def 'array hosts in the group that are not cluster members are reported, not removed'() {
        given:
        def hosts = nodes.collect { new ArrayHost(it.name.tokenize('.')[0], it.iqns, 'hg', 0) } +
                [new ArrayHost('old-node', ['iqn.x:old'], 'hg', 0)]

        when:
        def p = planner.plan('hg', nodes, hosts, ['hg'])

        then:
        p.strayHosts == ['old-node']
        !p.hosts*.arrayHost.contains('old-node')
    }

    // hvm-node03, 2026-10-21: rebuilt with a new initiator name; the array host named after it still has the old IQN
    static final String NEW_IQN = 'iqn.2026-10.com.hpe:hvm-node03:5947'
    static final String OLD_IQN = 'iqn.2024-12.com.hpe:hvm-node03:36307'
    def rebuilt = [new Node('hvm-node03', [NEW_IQN])]

    def 'rebuilt host: its same-named array host gets the new IQN instead of a -iscsi duplicate'() {
        when:
        def p = planner.plan('cluster-c', rebuilt, [new ArrayHost('hvm-node03', [OLD_IQN], 'cluster-c', 0)], ['cluster-c'])

        then:
        p.hostGroup == 'cluster-c'          // the old host's group is reused, not cluster-c-iscsi
        !p.createHostGroup
        p.hosts*.action == [Action.REUSE]
        p.hosts[0].arrayHost == 'hvm-node03'
        p.hosts[0].detail.contains(NEW_IQN)
        p.warnings.any { it.contains(OLD_IQN) }
        !p.blocked
    }

    def 'rebuilt host whose old array host sits in ANOTHER cluster\'s host group is a conflict, never moved'() {
        when:
        def p = planner.plan('cluster-c', rebuilt, [new ArrayHost('hvm-node03', [OLD_IQN], 'hg-cluster-a', 0)], ['hg-cluster-a'])

        then:
        p.hosts*.action == [Action.CONFLICT]
        p.hosts[0].detail.contains('hg-cluster-a')
        p.blocked
    }

    def 'the stale Morpheus IQN alone is enough evidence, even when it is not HPE-shaped'() {
        given:
        def n = [new Node('hvm-node03', [NEW_IQN], ['iqn.1991-05.com.example:old-build'])]

        expect:
        planner.plan('cluster-c', n, [new ArrayHost('hvm-node03', ['iqn.1991-05.com.example:old-build'], null, 0)], []).hosts*.action == [Action.REUSE]
    }

    def 'a same-named array host that already has another NODE\'s IQN is not reused'() {
        given:
        def two = rebuilt + [new Node('hvm-node04', [OLD_IQN])]   // the old IQN now belongs to a real node

        when:
        def p = planner.plan('c', two, [new ArrayHost('hvm-node03', [OLD_IQN], null, 0)], [])

        then:
        p.hosts.find { it.node == 'hvm-node03' }.action == Action.CREATE
        p.hosts.find { it.node == 'hvm-node03' }.arrayHost == 'hvm-node03-iscsi'
    }
}
