package org.purehvm.morpheus

import org.purehvm.ops.HostRunner
import spock.lang.Specification

class IscsiFabricGuardSpec extends Specification {

    private static Map parsed(String out) {
        IscsiFabricGuard.parse(new HostRunner.Result(output: out))
    }

    def 'the iface to keep is the one the clean hosts use, most used first; a host with findings has no vote'() {
        given:
        Map clean1 = parsed('SESSION=192.0.2.10|iface_iSCSI-60\nSESSION=198.51.100.10|iface_iSCSI-62\nPATHS=1|0|0|0|0\n')
        Map clean2 = parsed('SESSION=192.0.2.10|iface_iSCSI-60\nSESSION=192.0.2.11|iface_iSCSI-60\nPATHS=1|0|0|0|0\n')
        Map dirty = parsed('SESSION=192.0.2.10|hvmbr3.60\nSESSION=192.0.2.10|iface_iSCSI-60\n' +
                'FOUND=192.0.2.10|hvmbr3.60|duplicate of the session via iface_iSCSI-60 on hvmbr3.60\nPATHS=1|0|0|0|0\n')

        expect:
        IscsiFabricGuard.preferred([clean1, clean2, dirty]) == ['iface_iSCSI-60', 'iface_iSCSI-62']
        IscsiFabricGuard.preferred([dirty]) == []          // no clean host: the host script decides (bound over default)
        dirty.found.size() == 1 && dirty.sessions.size() == 2
    }

    def 'what stays behind becomes an alarm; a clean pass clears it'() {
        expect:
        IscsiFabricGuard.alarmFor('h1', parsed('SESSION=192.0.2.10|default\nPATHS=1|0|0|0|0\n')) == null
        IscsiFabricGuard.alarmFor('h1', parsed('REMOVED=192.0.2.10|hvmbr3.60|duplicate\nPATHS=1|0|0|0|0\n')) == null
        IscsiFabricGuard.alarmFor('h1', parsed('KEPT=192.0.2.10|hvmbr3.60|a LUN has fewer than 3 active paths\n')).status == 'warning'
        IscsiFabricGuard.alarmFor('h1', parsed('ERROR=a LUN was left short\n')).status == 'error'
        IscsiFabricGuard.alarmFor('h1', parsed('FOUND=192.0.2.10|hvmbr3.60|duplicate\n')).message.contains('Remediate')
    }

    def 'settings that were never saved raise an alarm; one saved value clears it'() {
        expect:
        ComplianceMonitor.settingsAlarm(settings)?.status == status

        where:
        settings                            | status
        [:]                                 | 'warning'
        null                                | 'warning'
        [tagNamespace: '', autoAddHosts: null] | 'warning'
        [multipathNoPathRetry: '8']         | null
    }
}
