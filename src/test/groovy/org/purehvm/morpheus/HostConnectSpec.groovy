package org.purehvm.morpheus

import org.purehvm.ops.HostRunner
import spock.lang.Specification

class HostConnectSpec extends Specification {

    def 'a portal is offered as a Morpheus target only when every host reaches it directly'() {
        given:
        List<String> portals = ['192.0.2.10', '198.51.100.10', '203.0.113.10']
        Map<String, HostRunner.Result> byHost = [
                'hvm-node01': new HostRunner.Result(0, 'ROUTE=192.0.2.10|hvmbr0.60|\nROUTE=198.51.100.10|hvmbr0.62|\nROUTE=203.0.113.10|mgmt|192.0.2.254\n'),
                'hvm-node02': new HostRunner.Result(0, 'ROUTE=192.0.2.10|hvmbr0.60|\nROUTE=198.51.100.10||\nROUTE=203.0.113.10|mgmt|192.0.2.254\n'),
        ]

        when:
        List<Map> r = HostConnect.portalReach(portals, byHost)

        then:
        r[0].everyHost && r[0].direct == ['hvm-node01', 'hvm-node02']
        !r[1].everyHost && r[1].direct == ['hvm-node01'] && r[1].unreachable == ['hvm-node02']
        !r[2].everyHost && r[2].gatewayed == ['hvm-node01', 'hvm-node02']
    }

    def 'the route check is read-only and names only the given portals'() {
        when:
        String s = HostConnect.routeScript(['192.0.2.10', '198.51.100.10'])

        then:
        s.startsWith('set +e\nfor p in 192.0.2.10 198.51.100.10; do')
        s.contains('ip -o route get "$p"')
        !(s =~ /iscsiadm|hvmcli|apt-get|systemctl/)
    }
}
