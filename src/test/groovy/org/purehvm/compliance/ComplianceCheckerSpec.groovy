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

    /** The sample with one map, no bad paths, and the given multipath values. */
    private HostFacts multipath(Map<String, String> v) {
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.mpDefaults = [polling_interval: '5']
        v.each { String k, String val ->
            if (k == 'stanza') { f.mpPure.no_path_retry = val }
            else if (k == 'defaults') { f.mpDefaults.no_path_retry = val }
            else if (k == 'overrides') { f.mpOverrides.no_path_retry = val }
            else if (k == 'polling') { val == null ? f.mpDefaults.remove('polling_interval') : f.mpDefaults.put('polling_interval', val) }
        }
        f
    }

    private ComplianceChecker.Result mp03(HostFacts f, Context c = ctx) {
        new ComplianceChecker().check(f, c).find { it.id == 'MP-03' }
    }

    private ComplianceChecker.Result mp03b(HostFacts f) {
        new ComplianceChecker().check(f, ctx).find { it.id == 'MP-03b' }
    }

    def 'the probe parser reads defaults and overrides, and multipathd\'s order decides the value'() {
        given:
        HostFacts f = HostFacts.parse('''@@mpconf
\tdevice {
\t\tvendor "PURE"
\t\tno_path_retry "fail"
\t}
@@mpglobal
defaults|polling_interval|10
defaults|no_path_retry|12
overrides|no_path_retry|queue
@@end
''')

        expect:
        f.mpDefaults.polling_interval == '10'
        f.noPathRetry == [value: 'queue', source: 'overrides']
        multipath(stanza: 'fail', defaults: '12').noPathRetry == [value: 'fail', source: 'device']
        multipath(defaults: '12').noPathRetry == [value: '12', source: 'defaults']
        multipath([:]).noPathRetry == [value: null, source: null]
    }

    def 'MP-03: fail or 0 warns, as Pure\'s written value and without claiming a proven failure'() {
        when:
        def r = mp03(multipath(stanza: value))

        then:
        r.status == Status.WARN
        r.observed == value
        r.fix == null                                     // default setting: Remediate would change nothing, so no button
        r.hint.contains("Pure's written value")
        r.hint.contains('about 20 s')
        r.hint.contains('Vendor guidance is pending')
        r.hint.contains('not yet under a real controller failover')

        where:
        value << ['fail', '0']
    }

    def 'MP-03: fail offers Remediate only when the setting asks for a finite value'() {
        given:
        Context c = new Context(ctx.portals, ctx.registeredIqns, ctx.purityVersion, '12')

        expect:
        mp03(multipath(stanza: 'fail'), c).with { fix == 'multipath' && hint.contains('asks for 12') }
        mp03(multipath(overrides: 'fail'), c).with { fix == null && hint.contains('overrides section') }
    }

    def 'MP-03: a finite value passes and shows its computed tolerance'() {
        when:
        def r = mp03(multipath(stanza: n, polling: polling))

        then:
        r.status == Status.PASS
        r.fix == null
        r.hint == null
        r.observed == "${n}: about ${seconds} s (20 s + ${n} x ${polling} s)".toString()
        r.expected.contains('5-30')

        where:
        n    | polling | seconds
        '1'  | '5'     | 25
        '12' | '5'     | 80
        '6'  | '10'    | 80
        '18' | '5'     | 110   // exactly at the limit is not above it
    }

    def 'MP-03: a finite value above the limit warns, and the limit is the site\'s'() {
        given:
        Context strict = new Context(ctx.portals, ctx.registeredIqns, ctx.purityVersion, '0', 80)

        expect:
        mp03(multipath(stanza: '19')).with { status == Status.WARN && observed.contains('about 115 s') && hint.contains('limit of 110 s') && fix == null }
        mp03(multipath(stanza: '12', polling: '5')).status == Status.PASS   // 80 s
        mp03(multipath(stanza: '13', polling: '5'), strict).with { status == Status.WARN && hint.contains('limit of 80 s') }
    }

    def 'MP-03: Remediate is offered on a finite host value only when a finite setting differs, and never against overrides'() {
        given:
        Context twelve = new Context(ctx.portals, ctx.registeredIqns, ctx.purityVersion, '12')

        expect:
        mp03(multipath(stanza: '12'), twelve).with { status == Status.PASS && fix == null && hint == null }
        mp03(multipath(stanza: '6'), twelve).with { status == Status.PASS && fix == 'multipath' && hint.contains('asks for 12') }
        mp03(multipath(stanza: '60'), twelve).with { status == Status.WARN && fix == 'multipath' && hint.contains('limit of 110 s') && hint.contains('asks for 12') }
        mp03(multipath(overrides: '6'), twelve).with { fix == null && hint.contains('overrides section') }
        mp03(multipath(stanza: '6', polling: null), twelve).with { status == Status.WARN && fix == 'multipath' }
        mp03(multipath(stanza: '6')).with { fix == null && hint == null }   // the default setting 0 never lowers it
    }

    def 'MP-03: a value from defaults is judged and labelled, and a device entry cannot hide an override'() {
        expect:
        mp03(multipath(defaults: '12')).with { status == Status.PASS && observed.startsWith('12 (from defaults): about 80 s') }
        mp03(multipath(stanza: 'fail', overrides: 'queue')).with { status == Status.FAIL && observed == 'queue (from overrides)' && fix == null && hint.contains('overrides section') }
    }

    def 'MP-03: queue fails, and unset or unreadable is never a pass'() {
        expect:
        mp03(multipath(stanza: 'queue')).with { status == Status.FAIL && fix == 'multipath' }
        mp03(multipath([:])).with { status == Status.FAIL && observed == 'unset' && fix == 'multipath' }
        mp03(multipath(stanza: 'banana')).with { status == Status.FAIL && observed == 'banana' }
        mp03(multipath(stanza: '12', polling: null)).with { status == Status.WARN && observed.contains('tolerance is unknown') }
    }

    def 'MP-03b reads multipathd\'s live state: only "on" is for ever'() {
        given:
        HostFacts f = multipath(stanza: configured)
        f.maps = [[wwid: '3624a93701a2b3c4d5e6f000000011234', name: '3624a93701a2b3c4d5e6f000000011234', queueing: queueing, paths: '2']]
        f.paths = up ? [[wwid: '3624a93701a2b3c4d5e6f000000011234', dev: 'sdb', dmState: 'active', checker: 'ready', prio: '50', vendor: 'PURE,FlashArray,8888']]
                     : [[wwid: '3624a93701a2b3c4d5e6f000000011234', dev: 'sdb', dmState: 'failed', checker: 'faulty', prio: '0', vendor: 'PURE,FlashArray,8888']]

        when:
        def r = mp03b(f)

        then:
        r.status == status
        r.fix == fix
        r.observed.contains(note)

        where:
        configured | queueing | up    | status      | fix         | note
        'fail'     | 'off'    | true  | Status.PASS | null        | '1 map(s)'
        'fail'     | 'off'    | false | Status.PASS | null        | '1 map(s)'     // MP-08 reports the paths
        'fail'     | '-'      | true  | Status.PASS | null        | '1 map(s)'
        '12'       | '12 chk' | true  | Status.PASS | null        | '1 map(s)'
        'queue'    | 'on'     | true  | Status.FAIL | 'multipath' | 'queueing for ever'
        '12'       | '30 sec' | false | Status.FAIL | null        | 'I/O held for 30 sec more'
        '12'       | 'off'    | false | Status.FAIL | null        | 'retries ran out'
        '12'       | 'off'    | true  | Status.WARN | null        | 'loaded as off, configured 12'
        'fail'     | '12 chk' | true  | Status.WARN | null        | 'loaded with 12 retries, configured fail'
        'fail'     | 'banana' | true  | Status.WARN | null        | "unreadable queueing state 'banana'"
    }

    def 'MP-03b: a map out of paths says to look at the array first, and offers no fix'() {
        given:
        HostFacts f = multipath(stanza: '12')
        f.maps = [[wwid: '3624a93701a2b3c4d5e6f000000011234', name: '3624a93701a2b3c4d5e6f000000011234', queueing: '30 sec', paths: '0']]
        f.paths = []

        expect:
        mp03b(f).with { fix == null && hint.contains('look at the array') }
    }

    def 'MP-03b: a map queueing for ever because of an overrides entry gets no Remediate, since a drop-in cannot change it'() {
        given:
        HostFacts f = multipath(overrides: 'queue')
        f.maps = [[wwid: '3624a93701a2b3c4d5e6f000000011234', name: '3624a93701a2b3c4d5e6f000000011234', queueing: 'on', paths: '2']]

        expect:
        mp03b(f).with { status == Status.FAIL && fix == null }
    }

    def 'the no_path_retry setting accepts 0 or 1-60; anything else, queue included, is Pure\'s 0'() {
        expect:
        Remediation.noPathRetrySetting(setting) == value

        where:
        setting | value
        null    | '0'
        ''      | '0'
        '0'     | '0'
        'fail'  | '0'
        'queue' | '0'
        '-1'    | '0'
        '61'    | '0'
        'x'     | '0'
        '12'    | '12'
        ' 60 '  | '60'
        12      | '12'
    }

    def 'remediation never takes a finite value back to 0, wherever the host set it'() {
        expect:
        Remediation.dropInNoPathRetry(setting, multipath(host)) == written

        where:
        setting | host                       | written
        '0'     | [stanza: '12']             | '12'
        '0'     | [defaults: '12']           | '12'
        '0'     | [overrides: '12']          | '12'
        '0'     | [stanza: '5', defaults: '12'] | '5'   // the device entry is what maps use
        '0'     | [stanza: 'fail']           | '0'
        '0'     | [stanza: 'queue']          | '0'
        '0'     | [:]                        | '0'
        '12'    | [stanza: 'fail']           | '12'
        '12'    | [stanza: 'queue']          | '12'
        '12'    | [stanza: '30']             | '12'  // a finite setting is the site's explicit choice
    }

    def 'the drop-in carries the kept value, the job log says so, and /etc/multipath.conf is never written'() {
        when:
        def s = new Remediation().scriptSteps(multipath(stanza: '12'), ['multipath'], [], '6.10.4')*.script.join('')
        def dropIn = new String(s.find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d/) { all, b64 -> b64 }.decodeBase64(), 'UTF-8')

        then:
        dropIn.contains('no_path_retry         12')
        !dropIn.contains('no_path_retry         0')
        s.contains('no_path_retry 12 (kept from this host; the setting asks for 0)')
        s.contains('> /etc/multipath/conf.d/pure.conf.new')
        !s.contains('/etc/multipath.conf')
    }

    def 'the setting reaches the drop-in'() {
        when:
        def s = new Remediation().scriptSteps(multipath(stanza: 'fail'), ['multipath'], [], '6.10.4', '6')*.script.join('')
        def dropIn = new String(s.find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d/) { all, b64 -> b64 }.decodeBase64(), 'UTF-8')

        then:
        dropIn.contains('no_path_retry         6')
        s.contains("echo 'no_path_retry 6'")
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

    /** The sample as an HVM 1.3 host: no hvmcli, so the plugin binds ifaces and logs in with iscsiadm itself. */
    private HostFacts withoutHvmcli(HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)) {
        f.hvmcli = false
        f
    }

    def 'without hvmcli the remediation script binds the missing iface, logs in missing sessions and writes drop-ins'() {
        given:
        HostFacts f = withoutHvmcli()
        def fixes = new ComplianceChecker().check(f, ctx).findAll { it.status == Status.FAIL && it.fix }*.fix.unique()

        when:
        def s = new Remediation().scriptSteps(f, fixes, ctx.portals, ctx.purityVersion)*.script.join('')

        then:
        s.contains('iscsiadm -m iface -I ens2f1.62 >/dev/null 2>&1 || iscsiadm -m iface -I ens2f1.62 --op=new')
        s.contains('iscsi_ok iscsiadm -m session --rescan')
        !s.contains('hvmcli storage iscsi')
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
        !rs.find { it.id == 'ISC-03' }.observed.contains('203.0.113.1')   // the gatewayed portals are not expected sessions
        !rs.any { it.id == 'NET-01' && it.title.contains('mgmt') }
    }

    def 'remediation skips gatewayed portals and a discovery timeout cannot abort it'() {
        when:
        def s = new Remediation().scriptSteps(withoutHvmcli(gatewayedFacts()), ['iscsi-sessions'], ctx.portals + ['203.0.113.10'], '6.10.4')*.script.join('')

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
        steps*.title == ['udev rules', 'iSCSI sessions via Morpheus (hvmcli, adds only)', 'node records: startup automatic, NOP-Out 5/5']
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

    /** A host Morpheus has just added: hvmcli, iSCSI NICs routed, nothing else (no sessions, no node records, no IQN). */
    static HostFacts freshHost(boolean openIscsi = true) {
        HostFacts.parse("""@@os
VERSION_ID=26.04
KERNEL=7.0.0-10-generic
@@pkgs
${openIscsi ? 'open-iscsi|2.1.11-1ubuntu1|ii ' : ''}
multipath-tools|0.14.3-1|ii
@@services
iscsid|inactive|disabled
multipathd|active|enabled
@@iqn
@@ifaces
@@sessions
@@nodes
@@mpconf
@@mpglobal
@@maps
@@paths
@@sysfs
@@routes
192.0.2.10|hvmbr0.60|
192.0.2.11|hvmbr0.60|
198.51.100.10|hvmbr0.62|
198.51.100.11|hvmbr0.62|
@@links
hvmbr0.60|9000
hvmbr0.62|9000
@@addrs
hvmbr0.60|192.0.2.21/24
hvmbr0.62|198.51.100.21/24
@@hvmcli
/usr/bin/hvmcli
@@end
""")
    }

    def 'a check that does not apply offers no fix: a fresh host is never sent node-startup (it stopped remediation on 0.12)'() {
        when:
        List<ComplianceChecker.Result> rs = new ComplianceChecker().check(freshHost(), ctx)
        List<String> offered = rs.findAll { it.fix }*.fix.unique()

        then:
        rs.find { it.id == 'ISC-04' }.status == Status.NA
        !offered.contains('node-startup')
        rs.findAll { it.status in [Status.PASS, Status.NA] }.every { it.fix == null }
        offered.containsAll(['iqn', 'services', 'iscsi-sessions', 'multipath'])
    }

    def 'node-startup and the session rescan treat iscsiadm "no records" (exit 21) as nothing to do'() {
        when:
        String s = new Remediation().scriptSteps(withoutHvmcli(freshHost()), ['node-startup', 'iscsi-sessions'], ctx.portals, '6.10.4')*.script.join('')

        then:
        s.contains('iscsi_ok() { local rc=0; "$@" || rc=$?; [ "$rc" -eq 0 ] || [ "$rc" -eq 21 ] || return "$rc"; }')
        s.contains('{ iscsi_ok iscsiadm -m node -P1 2>/dev/null; } | awk')
        s.contains('iscsi_ok iscsiadm -m session --rescan')
        !(s =~ /(?m)^iscsiadm -m session --rescan/)
    }

    def 'with hvmcli, sessions are made the way Morpheus makes them, only to its targets and only where missing'() {
        given:
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)   // logged in to 192.0.2.10 and 198.51.100.10

        when:
        String s = new Remediation().scriptSteps(f, ['iscsi-sessions'], ctx.portals, '6.10.4', '0',
                [targets: ['192.0.2.10', '192.0.2.11', '198.51.100.10']])*.script.join('')

        then:
        s.contains("hvmcli storage iscsi configure --portal '192.0.2.11:3260' --json")
        !s.contains("--portal '192.0.2.10:3260'") && !s.contains("--portal '198.51.100.10:3260'")   // already logged in
        s.contains("session to 198.51.100.10 already up")
        !s.contains("--portal '198.51.100.11:3260'")                 // not a Morpheus target of the cluster
        s.contains("SKIP portal 198.51.100.11: not a Morpheus iSCSI target")
        !s.contains('--initiator-name')                               // the host keeps its name
        !s.contains('iscsiadm -m iface')                              // Morpheus's virtual switch owns the ifaces
        s.contains('WARN hvmcli configure')                           // one portal failing does not stop the others
    }

    def 'when hvmcli configure fails, discovery and login fall back to the default iface only'() {
        given:
        HostFacts f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)

        when:
        String s = new Remediation().scriptSteps(f, ['iscsi-sessions'], ctx.portals, '6.10.4', '0',
                [targets: ['192.0.2.11']])*.script.join('')
        String fallback = s.substring(s.indexOf('WARN hvmcli configure 192.0.2.11 failed'))

        then: 'only after configure failed, only that portal, only the default iface, adding records without rewriting'
        s.indexOf("hvmcli storage iscsi configure --portal '192.0.2.11:3260'") < s.indexOf('-I default')
        fallback.contains('iscsiadm -m discovery -t sendtargets -p 192.0.2.11:3260 -I default -o new')
        fallback.contains('iscsiadm -m node -p 192.0.2.11:3260 -I default --login')
        (s =~ /--login/).count == 1
        !(s =~ /-I (?!default)\S+/)
        fallback.contains('discovery of 192.0.2.11 on the default iface failed too')
        !(s =~ /echo '[^'\n]*'[^\n]*'/)                               // no stray quote inside an echo
    }

    def 'with hvmcli and no Morpheus target list, every routed portal is wanted; gatewayed ones never'() {
        when:
        String s = new Remediation().scriptSteps(gatewayedFacts(), ['iscsi-sessions'], ctx.portals + ['203.0.113.10'], '6.10.4')*.script.join('')

        then:
        s.contains("--portal '192.0.2.11:3260'")
        s.contains("SKIP portal 203.0.113.10: only reachable through a gateway")
        !s.contains("--portal '203.0.113.10:3260'")
    }

    def 'an empty initiator name gets the IQN Morpheus has on record; a new one only when Morpheus has none'() {
        when:
        String withRecord = new Remediation().scriptSteps(freshHost(), ['iqn'], ctx.portals, '6.10.4', '0', [iqn: 'iqn.2026-11.com.hpe:hvm-node04:4321'])[0].script
        String noRecord = new Remediation().scriptSteps(freshHost(), ['iqn'], ctx.portals, '6.10.4', '0', [:])[0].script
        String hostile = new Remediation().scriptSteps(freshHost(), ['iqn'], ctx.portals, '6.10.4', '0', [iqn: "iqn.2026-11.com.hpe:x'; reboot; '"])[0].script

        then:
        withRecord.contains("IQN='iqn.2026-11.com.hpe:hvm-node04:4321'; SRC='Morpheus record'")
        withRecord.contains("grep -qE '^InitiatorName=iqn\\.'")       // still never replaces an existing name
        !withRecord.contains('RANDOM')
        noRecord.contains('RANDOM') && noRecord.contains("Morpheus had none")
        !hostile.contains('reboot') && hostile.contains('RANDOM')     // an unsafe record is not put in a script
    }

    def 'host scripts find hvmcli where HVM installs it (/usr/local/bin), as the probe must to detect it'() {
        given:
        String path = 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'

        expect:
        getClass().getResource('/scripts/pure-host-probe.sh').text.contains(path)
        Remediation.HEADER.contains(path)
        HostScripts.rescanAll().contains(path)
    }

    def 'packages are retried once after refreshing the package lists'() {
        when:
        String s = new Remediation().scriptSteps(freshHost(false), ['packages'], ctx.portals, '6.10.4')[0].script

        then:
        s.contains('if ! apt-get install -y -q $PKGS; then')
        s.contains('apt-get update -q && apt-get install -y -q $PKGS')
    }

    def 'on hvmcli hosts an unbound storage NIC points at the Morpheus iSCSI switch instead of offering a fix'() {
        when:
        def hv = new ComplianceChecker().check(facts, ctx).find { it.id == 'ISC-02' }
        def legacy = new ComplianceChecker().check(withoutHvmcli(), ctx).find { it.id == 'ISC-02' }

        then:
        hv.status == Status.FAIL && hv.fix == null && hv.hint.contains('iSCSI virtual switch')
        legacy.status == Status.FAIL && legacy.fix == 'iscsi-sessions'
    }

    def 'multipath change is refused while GFS2 is mounted through an alias'() {
        given:
        def f = HostFacts.parse(getClass().getResource('/probe-sample.txt').text)
        f.gfs2Mounts = [[source: '/dev/mapper/mpatha', target: '/mnt/x', options: 'rw']]

        expect:
        new Remediation().scriptSteps(f, ['multipath'], [], null)*.script.join('').contains('SKIP multipath')
    }
}
