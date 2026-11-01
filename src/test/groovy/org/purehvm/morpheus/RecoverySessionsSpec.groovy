package org.purehvm.morpheus

import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import spock.lang.Specification

/** Recovery sessions are rebuilt from clone tags; shapes from array01 (2026-10-21). */
class RecoverySessionsSpec extends Specification {
    static final long H = RecoverySessions.HOUR_MS

    def 'timeout comes from the plugin setting, default 8 hours'() {
        expect:
        RecoverySessions.timeoutHours([:]) == 8
        RecoverySessions.timeoutHours([recoveryTimeoutHours: '24']) == 24
        RecoverySessions.timeoutHours([recoveryTimeoutHours: 'soon']) == 8
        RecoverySessions.timeoutHours([recoveryTimeoutHours: '0']) == 8
    }

    def 'expiry: explicit tag (Keep open) wins, else created + timeout'() {
        expect:
        RecoverySessions.expiresAt([created: '1000', expires: '5000'], 8) == 5000
        RecoverySessions.expiresAt([created: '1000'], 8) == 1000 + 8 * H
        RecoverySessions.expiresAt([:], 8) == null
    }

    def 'old clones (no cluster tag) belong to the cluster whose host mounts them; new ones by cluster id'() {
        given:
        Map<String, String> old = [role: 'recovery-clone', source: 'array01-ds01', host: 'hvm-node01', created: '1792500000000']

        expect:
        RecoverySessions.belongsTo(old, 15L, ['hvm-node01', 'hvm-node02'])
        !RecoverySessions.belongsTo(old, 16L, ['hvm-node03'])
        RecoverySessions.belongsTo(old + ['recovery-cluster-id': '16'], 16L, [])
        !RecoverySessions.belongsTo(old + ['recovery-cluster-id': '16'], 15L, ['hvm-node01'])
        !RecoverySessions.belongsTo([role: 'gfs2-datastore', host: 'hvm-node01'], 15L, ['hvm-node01'])
    }

    def 'session tag from the tag, else from the clone name'() {
        expect:
        RecoverySessions.tagOf('array01-ds01-rcv-mqkq1h', [:]) == 'mqkq1h'
        RecoverySessions.tagOf('x-rcv-abc', [tag: 'zzz']) == 'zzz'
    }

    def 'file list of a rebuilt session is read-only and only from a recovery mount'() {
        expect:
        HostScripts.recoveryFiles('/mnt/pure-recover-mqkq1h').with { !(it =~ /\b(rm|umount|mount -t)\b/) && it.contains('-printf') }
        RecoveryOps.parseFiles(new HostRunner.Result(0, 'FILE=1975000000|1792512345.5|/mnt/pure-recover-mqkq1h/vm/hvm_2-disk-0-1b2c3d4\n')) ==
                [[size: 1975000000L, mtime: 1792512345L, path: '/mnt/pure-recover-mqkq1h/vm/hvm_2-disk-0-1b2c3d4']]

        when:
        HostScripts.recoveryFiles('/mnt/aaaaaaaa-1111-4222-8333-444444444444')   // a production mount

        then:
        thrown(IllegalArgumentException)
    }
}
