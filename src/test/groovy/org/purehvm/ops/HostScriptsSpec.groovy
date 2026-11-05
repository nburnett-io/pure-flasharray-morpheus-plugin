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
    def 'guest freeze: only running VMs with a disk on the datastore, a safety thaw timer before each freeze, never the host filesystem'() {
        when:
        def s = HostScripts.guestFreeze(MP, 'abc123')

        then:
        s.contains('virsh list --name')                                  // running domains only
        s.contains('index($4, mp)==1')                                    // a disk under this datastore
        s.indexOf('systemd-run') < s.indexOf('virsh domfsfreeze')
        s.contains("--on-active=${HostScripts.FREEZE_SAFETY_SEC} virsh domfsthaw")
        s.contains('timeout 15 virsh domfsfreeze')
        !s.contains('fsfreeze -f') && !s.contains('gfs2_freeze')       // the host GFS2 is never frozen
    }

    def 'guest thaw stops the safety timers; unsafe names and tags are refused'() {
        expect:
        HostScripts.guestThaw(['vm-1', 'vm.2'], 'abc123').contains("virsh domfsthaw 'vm-1'")
        HostScripts.guestThaw(['vm-1'], 'abc123').contains('systemctl stop "pure-thaw-abc123-')

        when:
        HostScripts.guestThaw(['x;reboot'], 'abc123')

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.guestFreeze(MP, 'A B')

        then:
        thrown(IllegalArgumentException)
    }
    def 'a freeze that fails or times out is thawed at once and keeps its safety timer; a failed thaw keeps it too'() {
        when:
        def f = HostScripts.guestFreeze(MP, 'abc123')
        def t = HostScripts.guestThaw(['vm-1'], 'abc123')
        String failBranch = f.substring(f.indexOf('else', f.indexOf('virsh domfsfreeze')), f.indexOf('SKIPPED=$d|$(echo'))

        then:
        failBranch.contains('virsh domfsthaw "$d"')
        !failBranch.contains('systemctl stop')
        t.indexOf('systemctl stop') < t.indexOf('THAW_FAILED')       // stopped only in the success branch
        t.indexOf('THAWED=vm-1') < t.indexOf('systemctl stop')
    }
}
