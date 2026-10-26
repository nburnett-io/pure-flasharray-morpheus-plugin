package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.admin.MorpheusSynchronousAdminService
import com.morpheusdata.core.synchronous.admin.MorpheusSynchronousAuditLogService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import org.purehvm.PureFlashArrayPlugin
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/** Tiers: Provider 1 (master) -> Cloud 2 (owns clusters 15/16) -> Globex 4 (customer). */
class TenancySpec extends Specification {
    static final Account PROVIDER = new Account(id: 1, name: 'Provider', masterAccount: true)
    static final Account CLOUD = new Account(id: 2, name: 'Cloud', masterAccount: false)
    static final Account GLOBEX = new Account(id: 4, name: 'Globex', masterAccount: false)
    static final Account INITECH = new Account(id: 3, name: 'Initech', masterAccount: false)
    ComputeServerGroup cluster15 = new ComputeServerGroup(id: 15, name: 'cluster-a-old', owner: CLOUD)

    static User userOf(Account a) { new User(username: "u${a.id}", account: a) }

    def 'master tenant and the owning tenant operate; customers and unrelated tenants do not'() {
        expect:
        Tenancy.isOperator(userOf(PROVIDER), cluster15)
        Tenancy.isOperator(userOf(CLOUD), cluster15)
        !Tenancy.isOperator(userOf(GLOBEX), cluster15)
        !Tenancy.isOperator(userOf(INITECH), cluster15)
        !Tenancy.isOperator(null, cluster15)
        !Tenancy.isOperator(new User(username: 'x'), cluster15)
    }

    def 'the Operator tenants setting adds tenants by id only'() {
        expect:
        Tenancy.operatorTenantIds([operatorTenants: '3, 7;9 x']) == [3L, 7L, 9L] as Set
        Tenancy.operatorTenantIds([:]).isEmpty()
        Tenancy.isOperator(userOf(INITECH), cluster15, Tenancy.operatorTenantIds([operatorTenants: '3']))
    }

    def 'a customer calling an operator route gets 403 and the refusal is audited, even with Ops permission'() {
        given:
        def audit = Mock(MorpheusSynchronousAuditLogService)
        def admin = Stub(MorpheusSynchronousAdminService) { getAuditLog() >> audit }
        def clusters = Stub(MorpheusSynchronousComputeServerGroupService) { get(15L) >> cluster15 }
        def services = Stub(MorpheusServices) { getCluster() >> clusters; getAdmin() >> admin }
        def morpheus = Stub(MorpheusContext) { getServices() >> services; getSettings(_) >> Single.just('{}') }
        def controller = new PureController(new PureFlashArrayPlugin(), morpheus)
        def vm = new ViewModel<Map>(object: [clusterId: '15'], user: userOf(GLOBEX))

        when:
        def r = controller.arrays(vm)

        then:
        r.status == 403
        (r.data as Map).msg.contains('reserved for the tenant that runs it')
        1 * audit.log(_, { it.description.contains('refused') && it.account.id == 4L })
    }

    def 'audit text names the action and cluster and drops CSRF and the cluster id'() {
        expect:
        Audit.describe('datastore-grow started', cluster15, [_csrf: 'secret', clusterId: '15', volume: 'v1', sizeGiB: 4096,
                                                             urlName: 'pure-hvm/datastore-grow', controller: 'pluginManager', action: 'route']) ==
                'Pure FlashArray: datastore-grow started on cluster cluster-a-old (15) [volume=v1, sizeGiB=4096]'
    }

    def 'a failing audit write never fails the operation'() {
        given:
        def admin = Stub(MorpheusSynchronousAdminService) { getAuditLog() >> { throw new IllegalStateException('db down') } }
        def morpheus = Stub(MorpheusContext) { getServices() >> Stub(MorpheusServices) { getAdmin() >> admin } }

        when:
        Audit.info(morpheus, userOf(CLOUD), cluster15, 'anything')

        then:
        notThrown(Exception)
    }
}
