package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationNotificationService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class ComplianceMonitorSpec extends Specification {

    static Map res(String id, String status, String severity = 'HIGH') {
        [id: id, title: "title ${id}".toString(), status: status, severity: severity]
    }

    def setup() {
        ComplianceMonitor.LAST.clear()
    }

    def 'a passing host carries no alarm'() {
        expect:
        ComplianceMonitor.alarmFor([host: 'h', results: [res('NET-01', 'PASS'), res('LIB-01', 'WARN', 'MEDIUM')]]) == null
    }

    def 'HIGH failures raise an error alarm that names them'() {
        when:
        Map a = ComplianceMonitor.alarmFor([host: 'h', results: [res('UDV-01', 'FAIL'), res('NET-02', 'FAIL', 'MEDIUM'), res('MP-01', 'PASS')]])

        then:
        a.status == 'error'
        a.message.contains('2 check(s) failing')
        a.message.contains('UDV-01 title UDV-01')
        !a.message.contains('MP-01')
    }

    def 'only MEDIUM failures are a warning, and a long list is cut after five'() {
        when:
        Map a = ComplianceMonitor.alarmFor([host: 'h', results: (1..7).collect { res("X-0${it}", 'FAIL', 'MEDIUM') }])

        then:
        a.status == 'warning'
        a.message.contains('X-05') && !a.message.contains('X-06')
        a.message.contains('and 2 more')
    }

    def 'a probe that did not finish is a warning, whatever the checks say'() {
        expect:
        ComplianceMonitor.alarmFor([host: 'h', error: 'the probe did not finish (timeout)', results: [res('A', 'FAIL')]]).with {
            status == 'warning' && message.contains('could not run')
        }
    }

    def 'interval: default 24 h, 0 turns it off'() {
        expect:
        ComplianceMonitor.intervalHours(settings) == hours

        where:
        settings                        | hours
        [:]                             | 24
        [complianceCheckHours: '0']     | 0
        [complianceCheckHours: '6']     | 6
        [complianceCheckHours: 'often'] | 24
        [complianceCheckHours: '-3']    | 0
    }

    PureClusterContext context(MorpheusSynchronousOperationNotificationService alarms) {
        def cluster = new ComputeServerGroup(id: 15L, name: 'cluster-a-old', owner: new Account(id: 2L))
        def clusters = Stub(MorpheusSynchronousComputeServerGroupService) { get(15L) >> cluster }
        def servers = Stub(MorpheusSynchronousComputeServerService) {
            list(_) >> [new ComputeServer(id: 49L, name: 'hvm-a'), new ComputeServer(id: 50L, name: 'hvm-b')]
        }
        def services = Stub(MorpheusServices) { getCluster() >> clusters; getComputeServer() >> servers; getOperationNotification() >> alarms }
        new PureClusterContext(Stub(MorpheusContext) { getServices() >> services }, 15L)
    }

    def 'a failing host gets an alarm, a host that passes again loses its old one'() {
        given:
        def alarms = Mock(MorpheusSynchronousOperationNotificationService)
        def ctx = context(alarms)
        def old = new OperationNotification(uniqueId: 'pure-hvm-compliance-50', active: true, status: 'error', statusMessage: 'old failure')

        when:
        Map out = ComplianceMonitor.record(ctx.morpheus, ctx, [[host: 'hvm-a', results: [res('UDV-01', 'FAIL')]],
                                                               [host: 'hvm-b', results: [res('UDV-01', 'PASS')]]], 1000L)

        then:
        2 * alarms.list(_) >>> [[], [old]]
        1 * alarms.createAlarm({ OperationNotification n ->
            n.uniqueId == 'pure-hvm-compliance-49' && n.refType == 'computeServer' && n.refId == 49L && n.status == 'error' &&
                    n.account.id == 2L && n.statusMessage.contains('UDV-01')
        }) >> ServiceResponse.success()
        1 * alarms.clearAlarm(old)
        0 * alarms.createAlarm({ it.refId == 50L })
        out.at == 1000L
        out.hosts.find { it.host == 'hvm-a' }.checks == ['UDV-01']
        out.hosts.find { it.host == 'hvm-b' }.failed == 0
    }

    def 'an unchanged alarm is left alone'() {
        given:
        def alarms = Mock(MorpheusSynchronousOperationNotificationService)
        def ctx = context(alarms)
        Map view = [host: 'hvm-a', results: [res('UDV-01', 'FAIL')]]
        Map a = ComplianceMonitor.alarmFor(view)
        def open = new OperationNotification(uniqueId: 'pure-hvm-compliance-49', active: true, status: a.status, statusMessage: a.message)

        when:
        ComplianceMonitor.record(ctx.morpheus, ctx, [view])

        then:
        1 * alarms.list(_) >> [open]
        0 * alarms.clearAlarm(_)
        0 * alarms.createAlarm(_)
    }

    def 'a one-host check keeps the others and does not count as the full run'() {
        given:
        def ctx = context(Stub(MorpheusSynchronousOperationNotificationService) { list(_) >> [] })
        ComplianceMonitor.record(ctx.morpheus, ctx, [[host: 'hvm-a', results: []], [host: 'hvm-b', results: [res('X', 'FAIL')]]], 1000L)

        when:
        Map out = ComplianceMonitor.record(ctx.morpheus, ctx, [[host: 'hvm-b', results: []]], 5000L)

        then:
        out.at == 1000L
        out.hosts*.host == ['hvm-a', 'hvm-b']
        out.hosts.find { it.host == 'hvm-b' }.failed == 0
    }

    def 'not due yet: the automatic run waits for the interval'() {
        given:
        def ctx = context(Stub(MorpheusSynchronousOperationNotificationService))
        ComplianceMonitor.LAST[15L] = [at: 1000L, hosts: []]

        when: 'an hour later, with a 24 h interval (a run would need the arrays and throw here)'
        ComplianceMonitor.runIfDue(ctx.morpheus, ctx, [:], 1000L + RecoverySessions.HOUR_MS)

        then:
        ComplianceMonitor.LAST[15L].at == 1000L
    }
}
