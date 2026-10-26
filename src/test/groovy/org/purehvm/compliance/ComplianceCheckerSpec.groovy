package org.purehvm.compliance

import org.purehvm.compliance.ComplianceChecker.Context
import org.purehvm.compliance.ComplianceChecker.Status
import org.purehvm.ops.HostScripts
import spock.lang.Shared
import spock.lang.Specification

class ComplianceCheckerSpec extends Specification {
    @Shared HostFacts facts = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
    @Shared Context ctx = new Context(['192.0.2.10', '192.0.2.11', '198.51.100.10', '198.51.100.11'], ['iqn.2024-12.com.hpe:hvm01:4f2a'] as Set, '6.10.4')
    @Shared List<ComplianceChecker.Result> results = new ComplianceChecker().check(facts, ctx)

    Status status(String id) {
        results.find { it.id == id }?.status
    }

    def 'parser reads every section'() {
        expect:
        facts.complete
        facts.iqn == 'iqn.2024-12.com.hpe:hvm01:4f2a'
        facts.ifaces.find { it.name == 'ens2f0.60' }.netdev == 'ens2f0.60'
        facts.ifaces.find { it.name == 'default' }.netdev == null
        facts.sessions.size() == 2
        facts.sessions[1].iface == 'default'
        facts.nodes.size() == 2
        facts.mpPure.path_selector == 'service-time 0'
        facts.storageInterfaces == ['ens2f0.60', 'ens2f1.62'] as Set
        facts.gfs2Tables.size() == 2
        facts.hvmcli
    }

    def 'healthy items pass'() {
        expect:
        results.findAll { it.id == 'PKG-01' }.every { it.status == Status.PASS }
        status('ISC-01') == Status.PASS
        status('NET-00') == Status.PASS
        status('GFS-02') == Status.PASS
        status('GFS-01') == Status.PASS
        status('UDV-04') == Status.PASS
    }

    def 'second NIC has no iface binding'() {
        expect:
        results.find { it.id == 'ISC-02' }.with { status == Status.FAIL && observed.contains('ens2f1.62') }
    }

    def 'two portals have no session'() {
        expect:
        results.find { it.id == 'ISC-03' }.with { status == Status.FAIL && observed.contains('192.0.2.11') && observed.contains('198.51.100.11') }
    }

    def 'manual node.startup is flagged'() {
        expect:
        status('ISC-04') == Status.FAIL
    }

    def 'missing no_path_retry in the effective stanza fails MP-03'() {
        expect:
        results.find { it.id == 'MP-03' }.with { status == Status.FAIL && observed == 'unset' }
    }

    def 'alias-named map fails MP-05'() {
        expect:
        results.find { it.id == 'MP-05' }.with { status == Status.FAIL && observed.contains('mpatha') }
    }

    def 'faulty path fails MP-08 for that map only'() {
        expect:
        results.findAll { it.id == 'MP-08' }*.status as Set == [Status.PASS, Status.FAIL] as Set
    }

    def 'mq-deadline scheduler fails UDV-01'() {
        expect:
        results.find { it.id == 'UDV-01' }.with { status == Status.FAIL && observed.contains('sdb') }
    }

    def 'MTU 1500 on the second fabric and failed jumbo pings'() {
        expect:
        results.find { it.id == 'NET-01' && it.title.contains('ens2f1.62') }.status == Status.FAIL
        results.findAll { it.id == 'NET-02' && it.status == Status.FAIL }.size() == 2
    }

    def 'MTU failures say how to fix them, never as an automatic fix'() {
        expect:
        facts.netplanFiles['ens2f1.62'] == '/etc/netplan/99-hvmcli-virtswitch-vs0.yaml'
        results.find { it.id == 'NET-01' && it.title.contains('ens2f1.62') }.with {
            fix == null && hint.contains('hvmcli virtswitch edit --virtswitch-name vs0 --mtu 9000') && hint.contains('maintenance')
        }
        results.find { it.id == 'NET-01' && it.title.contains('ens2f0.60') }.hint == null
        results.find { it.id == 'NET-02' && it.title.contains('198.51.100.10') }.hint.startsWith('Fix NET-01 (MTU on ens2f1.62) first')
        results.find { it.id == 'NET-02' && it.title.contains('192.0.2.10') }.hint == null
    }

    def 'jumbo ping failing with host MTU 9000 blames the switch'() {
        given:
        def f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.mtu['ens2f1.62'] = 9000

        expect:
        new ComplianceChecker().check(f, ctx).find { it.id == 'NET-02' && it.title.contains('198.51.100.10') }.hint.contains('fix it on the switch')
    }

    def 'MTU hint follows whoever owns the network config'() {
        expect:
        ComplianceChecker.mtuHint('bond0.60', '/etc/netplan/01-base.yaml').contains('in /etc/netplan/01-base.yaml, then sudo netplan apply')
        ComplianceChecker.mtuHint('bond0.60', '').contains('wherever this host\'s network is defined')
        ComplianceChecker.mtuHint('bond0.60', null).contains('wherever')
    }

    def 'separate subnets: no ARP checks emitted'() {
        expect:
        !results.any { it.id == 'NET-04' }
    }

    def 'duplicate lock table means an un-resignatured clone is visible'() {
        expect:
        results.find { it.id == 'GFS-03' }.with { status == Status.FAIL && observed.contains('hvmclu:ds01') }
    }

    def 'detect_prio rule'() {
        expect:
        ComplianceChecker.needsDetectPrioNo('0.8.8-1ubuntu1.22.04.4', '6.4.10')
        !ComplianceChecker.needsDetectPrioNo('0.8.8-1ubuntu1.22.04.4', '6.10.4')
        !ComplianceChecker.needsDetectPrioNo('0.9.4-5ubuntu8', '6.6.4')
        ComplianceChecker.needsDetectPrioNo('0.9.4-5ubuntu8', '6.5.3')
        !ComplianceChecker.needsDetectPrioNo('0.9.9-1', null)
    }

    def 'subnet math'() {
        expect:
        ComplianceChecker.network('192.0.2.200/25') == '192.0.2.128/25'
        ComplianceChecker.sharedSubnet(['a', 'b'], [a: ['192.0.2.21/25'], b: ['192.0.2.100/25']])
        !ComplianceChecker.sharedSubnet(['a', 'b'], [a: ['192.0.2.21/24'], b: ['198.51.100.21/24']])
    }

    def 'remediation script binds the missing iface, logs in missing sessions and writes drop-ins'() {
        given:
        def fixes = results.findAll { it.status == Status.FAIL && it.fix }*.fix.unique()

        when:
        def s = new Remediation().scriptSteps(facts, fixes, ctx.portals, ctx.purityVersion)*.script.join('')

        then:
        s.contains('iscsiadm -m iface -I ens2f1.62 --op=new')
        s.contains('iscsiadm -m node -p 192.0.2.11:3260 -I ens2f0.60 --login')
        !s.contains('iscsiadm -m node -p 192.0.2.10:3260 -I ens2f0.60 --login')   // already logged in
        s.contains('iscsiadm -m node -p 198.51.100.10:3260 -I ens2f0.60 -o delete')  // cross-fabric record
        s.contains(Remediation.MULTIPATH_DROPIN)
        s.contains('multipathd reconfigure')
        s.contains(Remediation.UDEV_RULES)
        !s.contains('logout')
        !s.contains('multipath -F')
    }

    def 'multipath drop-in carries the Pure stanza and detect_prio only when needed'() {
        expect:
        Remediation.multipathDropIn(true).contains('detect_prio           no')
        !Remediation.multipathDropIn(false).contains('detect_prio')
        Remediation.multipathDropIn(false).contains('no_path_retry         0')
        Remediation.multipathDropIn(false).contains('user_friendly_names   no')
    }

    /** hvm-node01's layout: NICs on the 60 and 62 fabrics; the array's third portal subnet only via mgmt's gateway. */
    static HostFacts gatewayedFacts() {
        String text = ComplianceCheckerSpec.getResource('/probe-sample.txt').text.replace('''198.51.100.11|ens2f1.62
''', '''198.51.100.11|ens2f1.62
203.0.113.10|mgmt|203.0.113.254
203.0.113.11|mgmt|203.0.113.254
''')
        HostFacts.parse(text)
    }

    def 'a portal behind a gateway is not a storage path: no session demanded, mgmt is not a storage NIC'() {
        given:
        HostFacts f = gatewayedFacts()
        Context c = new Context(ctx.portals + ['203.0.113.10', '203.0.113.11'], ctx.registeredIqns, ctx.purityVersion)
        List<ComplianceChecker.Result> rs = new ComplianceChecker().check(f, c)

        expect:
        f.gatewayed == ['203.0.113.10': 'mgmt via 203.0.113.254', '203.0.113.11': 'mgmt via 203.0.113.254']
        f.storageInterfaces == ['ens2f0.60', 'ens2f1.62'] as Set
        rs.find { it.id == 'NET-00' }.status == Status.PASS
        rs.find { it.id == 'NET-00b' }.with { status == Status.WARN && observed.contains('203.0.113.10 (mgmt via 203.0.113.254)') }
        !rs.find { it.id == 'ISC-03' }.observed.contains('10.64')
        !rs.any { it.id == 'NET-01' && it.title.contains('mgmt') }
    }

    def 'remediation skips gatewayed portals and a discovery timeout cannot abort it'() {
        when:
        def s = new Remediation().scriptSteps(gatewayedFacts(), ['iscsi-sessions'], ctx.portals + ['203.0.113.10'], '6.10.4')*.script.join('')

        then:
        s.contains("SKIP portal 203.0.113.10: only reachable through a gateway (mgmt via 203.0.113.254)")
        !s.contains('-p 203.0.113.10:3260')
        !s.contains('-I mgmt')
        s.contains("if timeout ${Remediation.DISCOVERY_TIMEOUT_SEC} iscsiadm -m discovery -t sendtargets -p 192.0.2.11:3260".toString())
        s.contains('WARN discovery of 192.0.2.11')
    }

    def 'an unbound session printing "Iface Netdev: default" (Ubuntu 24.04+) is routed by the kernel, not via a NIC named default'() {
        when:
        HostFacts f = HostFacts.parse('''@@sessions
Target: iqn.2010-06.com.purestorage:flasharray.1234 (non-flash)
\tCurrent Portal: 192.0.2.10:3260,1
\t\tIface Name: default
\t\tIface Netdev: default
\t\tiSCSI Session State: LOGGED_IN
@@routes
192.0.2.10|ens2f0.60|
@@end
''')

        then:
        f.sessions[0].netdev == null
        f.sessions[0].viaNic == 'ens2f0.60'
    }

    def 'a host without an initiator name fails ISC-00 and Remediate sets one only when none is set'() {
        given:
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.iqn = null

        when:
        def r = new ComplianceChecker().check(f, ctx).find { it.id == 'ISC-00' }
        def steps = new Remediation().scriptSteps(f, ['iqn', 'iscsi-sessions'], ctx.portals, '6.10.4')

        then:
        r.status == Status.FAIL && r.fix == 'iqn'
        status('ISC-00') == Status.PASS
        steps[0].title == 'iSCSI initiator name'                       // before any session is logged in
        steps[0].script.contains("grep -qE '^InitiatorName=iqn\\.'")     // never overwrites an existing name
        steps[0].script.contains('systemctl restart iscsid')
    }

    def 'a libvirt pool left for an unmounted GFS2 datastore is flagged; local dir pools and mounted ones are not'() {
        given:
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text.replace('@@files', '''@@pools
local|/var/morpheus/kvm/local|running
array01-ds01|/mnt/dddddddd-4444-4555-8666-777777777777|running
array01-test|/mnt/bbbbbbbb-2222-4333-8444-555555555555|inactive
@@files'''))

        when:
        def r = new ComplianceChecker().check(f, ctx).find { it.id == 'LIB-01' }
        def steps = new Remediation().scriptSteps(f, ['stale-pools'], ctx.portals, '6.10.4')

        then:
        f.pools.size() == 3
        r.status == Status.WARN && r.fix == 'stale-pools'
        r.observed.contains('array01-test') && !r.observed.contains('local') && !r.observed.contains('HVM-02')
        steps.size() == 1
        steps[0].script.contains("PMP='/mnt/bbbbbbbb-2222-4333-8444-555555555555'")
        steps[0].script.indexOf('mountpoint -q') < steps[0].script.indexOf('pool-undefine')   // never a mounted datastore
        steps[0].script.contains('is not empty; pool left alone')
        !steps[0].script.contains('dddddddd')
    }

    def 'pool removal refuses anything but a GFS2 datastore path'() {
        when:
        HostScripts.removePools('/var/morpheus/kvm/local')

        then:
        thrown(IllegalArgumentException)
    }

    def 'remediation runs as one script per fix, each self-contained'() {
        when:
        def steps = new Remediation().scriptSteps(facts, ['udev', 'iscsi-sessions', 'node-startup'], ctx.portals, '6.10.4')

        then:
        steps*.title == ['udev rules', 'iSCSI iface binding and sessions (adds only)', 'node records: startup automatic, NOP-Out 5/5']
        steps.every { it.script.startsWith('#!/bin/bash\nset -euo pipefail') }
        steps[0].script.contains(Remediation.UDEV_RULES) && !steps[0].script.contains('iscsiadm')
    }

    def 'an open recovery clone (private lock table) is not a GFS-03 hazard; an unmounted duplicate still is'() {
        given:
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.gfs2Mounts << [source: '/dev/mapper/3624a93701a2b3c4d5e6f000000015678', target: '/mnt/pure-recover-abc123',
                         options: 'ro,lockproto=lock_nolock,locktable=recover:abc123']

        expect:
        new ComplianceChecker().check(f, ctx).find { it.id == 'GFS-03' }.status == Status.PASS
        status('GFS-03') == Status.FAIL   // the sample without the recovery mount
    }

    def 'multipath change is refused while GFS2 is mounted through an alias'() {
        given:
        def f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.gfs2Mounts = [[source: '/dev/mapper/mpatha', target: '/mnt/x', options: 'rw']]

        expect:
        new Remediation().scriptSteps(f, ['multipath'], [], null)*.script.join('').contains('SKIP multipath')
    }
}
