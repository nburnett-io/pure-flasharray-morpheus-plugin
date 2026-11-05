package org.purehvm.compliance

import org.purehvm.compliance.ComplianceChecker.Context
import org.purehvm.compliance.ComplianceChecker.Status
import spock.lang.Specification

/**
 * A host whose iSCSI virtual switch was rebuilt (old ifaces on bridges that are down) and that an older plugin
 * remediated (ifaces named after the NIC, with spare node records): the shape seen on a real 2.0 host.
 */
class StaleRecordsSpec extends Specification {
    static final String T = 'iqn.2010-06.com.purestorage:flasharray.1a2b3c4d5e6f'
    Context ctx = new Context(['192.0.2.10', '198.51.100.10'], ['iqn.2026-11.com.hpe:hvm01:1234'] as Set, '6.10.4')

    static String node(String ip, String iface, String startup, String noop = '5') {
        """# BEGIN RECORD 2.1.11
node.name = ${T}
node.startup = ${startup}
iface.iscsi_ifacename = ${iface}
node.conn[0].address = ${ip}
node.conn[0].timeo.noop_out_interval = 5
node.conn[0].timeo.noop_out_timeout = ${noop}
# END RECORD
"""
    }

    static HostFacts facts(boolean withLinkState = true) {
        HostFacts.parse("""@@ifaces
default tcp,<empty>,<empty>,<empty>,<empty>
iser iser,<empty>,<empty>,<empty>,<empty>
br3.60 tcp,<empty>,<empty>,br3.60,<empty>
iface_iSCSI-60 tcp,<empty>,<empty>,br3.60,<empty>
iface_iSCSI-62 tcp,<empty>,<empty>,br4.62,<empty>
iface_iSCSI-A tcp,<empty>,<empty>,br1.60,<empty>
@@sessions
Target: ${T} (non-flash)
\tCurrent Portal: 192.0.2.10:3260,61
\t\tIface Name: iface_iSCSI-60
\t\tIface Netdev: br3.60
\t\tiSCSI Session State: LOGGED_IN
\tCurrent Portal: 198.51.100.10:3260,63
\t\tIface Name: iface_iSCSI-62
\t\tIface Netdev: br4.62
\t\tiSCSI Session State: LOGGED_IN
@@nodes
${node('192.0.2.10', 'br3.60', 'manual', '15')}${node('192.0.2.10', 'iface_iSCSI-60', 'automatic')}${node('198.51.100.10', 'iface_iSCSI-62', 'automatic')}@@routes
192.0.2.10|br3.60
198.51.100.10|br4.62
@@addrs
br3.60|192.0.2.21/24
br4.62|198.51.100.21/24
@@hvmcli
/usr/local/bin/hvmcli
${withLinkState ? '@@linkstate\nbr1.60|down\nbr3.60|up\nbr4.62|up\nlo|unknown\n' : ''}@@end
""")
    }

    def 'spare node records and dead or duplicate ifaces are found; live ones never are'() {
        given:
        HostFacts f = facts()

        expect:
        ComplianceChecker.spareNodes(f).collect { "${it['node.conn[0].address']}|${it['iface.iscsi_ifacename']}" } == ['192.0.2.10|br3.60']
        ComplianceChecker.deadIfaces(f)*.name == ['iface_iSCSI-A']
        ComplianceChecker.redundantIfaces(f, ComplianceChecker.spareNodes(f), ComplianceChecker.deadIfaces(f))*.name == ['br3.60']
    }

    def 'ISC-04 and ISC-06 judge only the records in use; ISC-07 fails on a dead iface when hvmcli is there'() {
        when:
        List<ComplianceChecker.Result> r = new ComplianceChecker().check(facts(), ctx)

        then: 'the spare record is manual with noop 15, but it is not the one in use'
        r.find { it.id == 'ISC-04' }.status == Status.PASS
        r.find { it.id == 'ISC-06' }.status == Status.PASS
        r.find { it.id == 'ISC-04' }.fix == null
        with(r.find { it.id == 'ISC-07' }) {
            status == Status.FAIL
            fix == 'stale-iscsi-records'
            observed.contains('192.0.2.10 via br3.60') && observed.contains('iface_iSCSI-A (br1.60)') && observed.contains('br3.60 (br3.60)')
        }
    }

    def 'an older probe without link states judges no iface dead'() {
        expect:
        ComplianceChecker.deadIfaces(facts(false)).isEmpty()
        new ComplianceChecker().check(facts(false), ctx).find { it.id == 'ISC-07' }.status == Status.WARN   // the spare record still shows
    }

    def 'the cleanup deletes record by record and iface by iface, each only if no session uses it at run time'() {
        when:
        String s = new Remediation().scriptSteps(facts(), ['stale-iscsi-records'], ctx.portals, '6.10.4')*.script.join('')

        then:
        s.contains("iscsiadm -m node -T '${T}' -p '192.0.2.10:3260' -I 'br3.60' -o delete")
        s.contains("iscsiadm -m iface -I 'iface_iSCSI-A' -o delete")
        s.contains("iscsiadm -m iface -I 'br3.60' -o delete")
        s.contains("grep -qxF '192.0.2.10|br3.60' <<<\"\$LIVE\"")      // re-checked when the step runs
        !s.contains("-I 'iface_iSCSI-60' -o delete") && !s.contains("-I 'iface_iSCSI-62' -o delete")
        !(s =~ /iscsiadm -m node (-I \S+ )?-o delete/)                   // never a delete by filter
        !s.contains('--logout') && !s.contains(' -u ')
        s.indexOf("-I 'br3.60' -o delete") < s.indexOf("iscsiadm -m iface -I 'br3.60' -o delete")   // records before their iface
    }

    def 'the cleanup runs before the sessions step (a dead iface makes hvmcli configure time out)'() {
        when:
        List<String> titles = new Remediation().scriptSteps(facts(), ['iscsi-sessions', 'stale-iscsi-records'], ctx.portals, '6.10.4')*.title

        then:
        titles.indexOf('remove unused and dead iSCSI records (never a logged-in one)') < titles.indexOf('iSCSI sessions via Morpheus (hvmcli, adds only)')
    }

    def 'node-startup updates only the record each live session uses, by iface'() {
        when:
        String s = new Remediation().scriptSteps(facts(), ['node-startup'], ctx.portals, '6.10.4')*.script.join('')

        then:
        s.contains(Remediation.LIVE_PAIRS)
        s.contains('iscsiadm -m node -P1')
        s.contains('-I "$iface" -o update')
        s.contains('the session to ${ip} uses another record')
    }

    def 'a value with unexpected characters is never put into the cleanup script'() {
        given:
        HostFacts f = facts()
        f.ifaces.find { it.name == 'iface_iSCSI-A' }.name = 'x;reboot'

        when:
        String s = new Remediation().scriptSteps(f, ['stale-iscsi-records'], ctx.portals, '6.10.4')*.script.join('')

        then:
        !s.contains('reboot')
        s.contains('SKIP an iface record with unexpected characters')
    }
    def 'node records are read without their END lines (what the probe filter used to leave of them)'() {
        when:
        HostFacts f = HostFacts.parse("@@nodes\n# BEGIN RECORD 2.1.11\nnode.name = ${T}\nnode.conn[0].address = 192.0.2.10\n" +
                "iface.iscsi_ifacename = default\n# BEGIN RECORD 2.1.11\nnode.name = ${T}\nnode.conn[0].address = 198.51.100.10\n@@end\n")

        then:
        f.nodes*.get('node.conn[0].address') == ['192.0.2.10', '198.51.100.10']
    }

    def 'the probe keeps the END RECORD lines'() {
        expect:
        getClass().getResource('/scripts/pure-host-probe.sh').text.contains('noop_out_timeout)( |$)')
    }
    def 'two sessions to one portal (one Current Portal heading, two interface blocks) are two sessions; ISC-03c reports them'() {
        when:
        HostFacts f = HostFacts.parse("""@@sessions
Target: ${T} (non-flash)
\tCurrent Portal: 192.0.2.10:3260,1
\t\tIface Name: default
\t\tIface Netdev: default
\t\tiSCSI Session State: LOGGED_IN
\t\tIface Name: iface_iSCSI-A
\t\tIface Netdev: br3.60
\t\tiSCSI Session State: LOGGED_IN
@@nodes
${node('192.0.2.10', 'default', 'automatic')}${node('192.0.2.10', 'iface_iSCSI-A', 'automatic')}@@routes
192.0.2.10|br3.60
@@end
""")
        def r = new ComplianceChecker().check(f, new Context(['192.0.2.10'], [] as Set, '6.10.4'))

        then:
        f.sessions*.iface == ['default', 'iface_iSCSI-A']
        f.sessions*.state == ['LOGGED_IN', 'LOGGED_IN']
        ComplianceChecker.spareNodes(f).isEmpty()                    // both records are in use
        r.find { it.id == 'ISC-03c' }.status == Status.WARN
        r.find { it.id == 'ISC-03c' }.observed == '192.0.2.10 via br3.60 (default, iface_iSCSI-A)'
    }
    def 'an iface on a NIC that is only down (still addressed) is reported but never removed'() {
        given:
        HostFacts f = facts()
        f.linkState['br4.62'] = 'down'                                   // fabric B in maintenance, address kept
        f.sessions.removeAll { it.iface == 'iface_iSCSI-62' }

        expect:
        !ComplianceChecker.deadIfaces(f)*.name.contains('iface_iSCSI-62')
        ComplianceChecker.downIfaces(f)*.name == ['iface_iSCSI-62']
        !new Remediation().scriptSteps(f, ['stale-iscsi-records'], ctx.portals, '6.10.4')*.script.join('').contains("-I 'iface_iSCSI-62' -o delete")
    }

    def 'only a down iface: ISC-07 warns without a fix'() {
        given:
        HostFacts f = HostFacts.parse("""@@ifaces
iface_iSCSI-62 tcp,<empty>,<empty>,br4.62,<empty>
@@addrs
br4.62|198.51.100.21/24
@@linkstate
br4.62|down
@@end
""")

        when:
        def r = new ComplianceChecker().check(f, ctx).find { it.id == 'ISC-07' }

        then:
        r.status == Status.WARN && r.fix == null && r.observed.contains('kept')
    }

    def 'the cleanup reports a delete that fails instead of failing the step'() {
        expect:
        new Remediation().scriptSteps(facts(), ['stale-iscsi-records'], ctx.portals, '6.10.4')*.script.join('').contains("|| echo 'WARN could not delete iface iface_iSCSI-A'")
    }
}
