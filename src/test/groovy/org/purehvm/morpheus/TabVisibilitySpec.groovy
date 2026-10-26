package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.User
import org.purehvm.PureFlashArrayPlugin
import spock.lang.Specification

/** Who gets the cluster tab (Cloud 2 owns cluster 15, Acme 7 is a customer). */
class TabVisibilitySpec extends Specification {
    static final Account CLOUD = new Account(id: 2, name: 'Cloud', masterAccount: false)
    static final Account ACME = new Account(id: 7, name: 'Acme', masterAccount: false)

    def 'the cluster tab shows on an HVM cluster for its owning tenant only'() {
        given:
        def tab = new PureClusterTabProvider(new PureFlashArrayPlugin(), Stub(MorpheusContext))
        def cluster = new ComputeServerGroup(id: 15, owner: CLOUD, type: new ComputeServerGroupType(code: code))

        expect:
        tab.show(cluster, new User(account: CLOUD), CLOUD) == forOwner
        !tab.show(cluster, new User(account: ACME), ACME)

        where:
        code             | forOwner
        'mvm-cluster'    | true
        'kvm-cluster'    | true
        null             | true
        'vmware-cluster' | false
    }

    def 'a partial cluster (no owner) and a user without its account still show the tab to the owner'() {
        given: 'what Morpheus can pass to show(): the full record is loaded from the services'
        def full = new ComputeServerGroup(id: 15, owner: CLOUD, type: new ComputeServerGroupType(code: 'mvm-cluster'))
        def clusters = Stub(MorpheusSynchronousComputeServerGroupService) { get(15L) >> full }
        def services = Stub(MorpheusServices) { getCluster() >> clusters }
        def tab = new PureClusterTabProvider(new PureFlashArrayPlugin(), Stub(MorpheusContext) { getServices() >> services })
        def partial = new ComputeServerGroup(id: 15, type: full.type)

        expect:
        tab.show(partial, new User(username: 'ops-user'), CLOUD)
        !tab.show(partial, new User(username: 'acme-admin'), ACME)
    }
}
