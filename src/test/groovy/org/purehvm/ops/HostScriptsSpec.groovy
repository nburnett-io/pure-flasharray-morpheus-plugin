package org.purehvm.ops

import spock.lang.Specification

class HostScriptsSpec extends Specification {
    static final String W = '3624a9370a1b2c3d4e5f6a7b8c9d0e1f2'
    static final String MP = '/mnt/aaaaaaaa-1111-4222-8333-444444444444'

    def 'mount for a late-joining host checks corosync membership and DLM before mounting'() {
        when:
        def s = HostScripts.mountGfs2(W, MP, 'rw,noatime,nodiratime,discard,localflocks,jid=1', 'pool-x', 'PHBvb2w+PC9wb29sPg==')

        then:
        s.indexOf('corosync-cmapctl') < s.indexOf('mount -t gfs2')
        s.indexOf('dlm_tool ls') < s.indexOf('mount -t gfs2')
        s.contains('does not match this host')
        s.contains('mount -t gfs2 -o noatime,nodiratime,discard,localflocks ')   // rw and jid= dropped
        !s.contains('jid=1')
        s.contains("virsh pool-define")
        s.contains("virsh pool-autostart 'pool-x'")
    }

    def 'mount is a no-op when already mounted'() {
        expect:
        HostScripts.mountGfs2(W, MP, null, null, null).contains('ALREADY=1')
    }

    def 'unsafe mount inputs are refused'() {
        when:
        HostScripts.mountGfs2(W, MP, 'noatime;reboot', null, null)

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.mountGfs2(W, MP, null, 'p; rm -rf /', null)

        then:
        thrown(IllegalArgumentException)
    }

    def 'storage state reports presence and mount target per WWID'() {
        when:
        def s = HostScripts.storageState([W])

        then:
        s.contains("STORAGE=${W}|")
        s.contains("mnt_of ${W}") && s.contains('mnt_of() { findmnt -rn -S "/dev/mapper/$1"')
    }

    def 'rescan never reloads existing maps'() {
        expect:
        !HostScripts.rescanAll().contains('multipath -r')
        !HostScripts.rescanAll().contains('multipath -F')
    }

    def 'rescans first remove dead maps, but never a mounted or open one (LUN-ID reuse on hvm-node03)'() {
        expect:
        [HostScripts.rescanAll(), HostScripts.rescan(W)].every { String s ->
            int clean = s.indexOf('STALE_REMOVED'), rescan = s.indexOf('session --rescan')
            clean > 0 && clean < rescan &&
                    s.contains('[ "$(healthy_paths $SW)" = "0" ] || continue') &&
                    s.indexOf('findmnt -rn -S /dev/mapper/$SW') < s.indexOf('multipath -f $SW') &&
                    s.indexOf('-o open $SW') < s.indexOf('multipath -f $SW')
        }
    }
}
