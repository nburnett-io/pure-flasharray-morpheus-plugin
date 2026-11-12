package org.purehvm.morpheus

import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import spock.lang.Specification

class Gfs2LockGuardSpec extends Specification {

    static Map m(String table, String proto, String mount) { [table: table, proto: proto, mount: mount] }

    def 'a lock_nolock mount of a cluster filesystem is reported, and named as live corruption when another host mounts it too'() {
        given:
        Map<String, List<Map>> mounts = [
                'host-a': [m('testclu:ds_01', 'lock_dlm', '/mnt/aaaaaaaa-1111-4222-8333-444444444444'),
                           m('testclu:ds_02', 'lock_nolock', '/mnt/bbbbbbbb-1111-4222-8333-444444444444')],
                'host-b': [m('testclu:ds_01', 'lock_dlm', '/mnt/aaaaaaaa-1111-4222-8333-444444444444'),
                           m('testclu:ds_02', 'lock_dlm', '/mnt/bbbbbbbb-1111-4222-8333-444444444444')],
                'host-c': [m('testclu:ds_03', 'lock_nolock', '/mnt/cccccccc-1111-4222-8333-444444444444')],
        ]

        when:
        Map<String, String> f = Gfs2LockGuard.findings(mounts)

        then:
        f.keySet() == ['host-a', 'host-c'] as Set
        f['host-a'].contains('testclu:ds_02') && f['host-a'].contains('host-b mounts it at the same time') && f['host-a'].contains('unmount it there now')
        f['host-c'].contains('no other host may mount it')
    }

    def 'the plugin\'s own recovery and re-sign mounts are not reported'() {
        expect:
        Gfs2LockGuard.findings(['host-a': [m('recover:k6po4q', 'lock_nolock', '/mnt/pure-recover-k6po4q'),
                                           m('testclu:ds_02_copy', 'lock_nolock', '/mnt/pure-resign-Ab12Cd'),
                                           m('testclu:ds_01', 'lock_dlm', '/mnt/aaaaaaaa-1111-4222-8333-444444444444')]]) == [:]
        // a recovery path name alone does not excuse a cluster lock table mounted elsewhere
        Gfs2LockGuard.findings(['host-a': [m('testclu:ds_02', 'lock_nolock', '/mnt/oldds')]]).size() == 1
    }

    def 'MTU apply script goes fabric by fabric, checks paths and jumbo frames, and reverts on failure'() {
        when:
        String s = HostScripts.applyStorageMtu(9000, ['192.0.2.10', '192.0.2.11'])

        then:
        s.contains('MTU=9000') && s.contains('PORTALS="192.0.2.10 192.0.2.11"')
        s.contains('hvmcli virtswitch edit --virtswitch-name "$1" --mtu "$2" --force')
        s.contains('"$(yv base_type "$F")" = iscsi')                 // only iSCSI virtSwitches
        s.contains('shares NIC')                                     // never a switch whose NICs another switch uses
        s.contains('ping -c3 -W2 -M do -s 8972')                     // jumbo frames verified at 9000
        s.contains('edit "$N" "$OLD"') && s.contains('REVERTED=')     // put back on failure
        s.contains('dev \\([^ ]*\\)')                                 // sed escapes survive the Groovy string
        !(s =~ /iscsiadm .*logout|multipath -F|systemctl restart|netplan apply|reboot/)

        when:
        HostScripts.applyStorageMtu(4000, [])

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.applyStorageMtu(1500, ['192.0.2.1; reboot'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'probe output is parsed and the probe is read-only'() {
        given:
        HostRunner.Result r = new HostRunner.Result(0, 'GFS2=testclu:ds_01|lock_dlm|/mnt/a\nGFS2=recover:x|lock_nolock|\n')

        expect:
        Gfs2LockGuard.parse(r) == [m('testclu:ds_01', 'lock_dlm', '/mnt/a'), m('recover:x', 'lock_nolock', '')]
        HostScripts.gfs2Locks().with { s -> s.contains('/sys/fs/gfs2') && s.contains('proto_name') && !(s =~ /\bumount\b|\bmount -t|\brm\b|\bkill\b|tunegfs2 -|(?<!2)>\s*\/(?!dev\/null)/) }
    }
}
