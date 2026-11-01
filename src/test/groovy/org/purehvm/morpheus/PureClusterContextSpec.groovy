package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.model.ComputeServerGroup
import spock.lang.Specification

/** Array links: a cluster can use several FlashArrays and an array can serve several clusters. */
class PureClusterContextSpec extends Specification {
    ComputeServerGroup c15 = new ComputeServerGroup(id: 15, name: 'cluster-a-old')
    ComputeServerGroup c16 = new ComputeServerGroup(id: 16, name: 'cluster-b')
    List<ComputeServerGroup> saved = []
    PureClusterContext ctx

    def setup() {
        def clusters = Stub(MorpheusSynchronousComputeServerGroupService) {
            get(15L) >> c15
            get(16L) >> c16
            list(_) >> [c15, c16]
            save(_) >> { args -> saved << args[0]; args[0] }
        }
        def services = Stub(MorpheusServices) { getCluster() >> clusters }
        ctx = new PureClusterContext(Stub(MorpheusContext) { getServices() >> services }, 15L)
    }

    def 'a 0.3 single link is read as one link and rewritten in the list form on save'() {
        given:
        c15.setConfigProperty('pureStorageServerId', 4)
        c15.setConfigProperty('pureHostGroup', 'hg-cluster-a')

        expect:
        ctx.links == [[storageServerId: 4L, hostGroup: 'hg-cluster-a']]

        when:
        ctx.setLink(7L, null)

        then:
        ctx.links == [[storageServerId: 4L, hostGroup: 'hg-cluster-a'], [storageServerId: 7L, hostGroup: null]]
        c15.getConfigProperty('pureStorageServerId') == null
        c15.getConfigProperty('pureHostGroup') == null
        saved == [c15]
    }

    def 'setting the host group of an existing link keeps the other links'() {
        given:
        c15.setConfigProperty('pureLinks', [[storageServerId: 4, hostGroup: null], [storageServerId: 7, hostGroup: 'hg-b']])

        when:
        ctx.setLink(4L, 'hg-a')

        then:
        ctx.links.sort { it.storageServerId } == [[storageServerId: 4L, hostGroup: 'hg-a'], [storageServerId: 7L, hostGroup: 'hg-b']]

        when:
        ctx.unlink(7L)

        then:
        ctx.links == [[storageServerId: 4L, hostGroup: 'hg-a']]
    }

    def 'two clusters may share an array but never a host group on it'() {
        given:
        c16.setConfigProperty('pureLinks', [[storageServerId: 4, hostGroup: 'hg-cluster-b']])

        when: 'same array, own host group'
        ctx.setLink(4L, 'hg-cluster-a')

        then:
        notThrown(IllegalStateException)

        when: 'same array, the other cluster\'s host group (case-insensitive, as Purity names are)'
        ctx.setLink(4L, 'hg-cluster-b')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('cluster-b')
        ctx.links == [[storageServerId: 4L, hostGroup: 'hg-cluster-a']]

        expect: 'a same-named host group on a different array is a different object'
        ctx.clusterUsing(9L, 'hg-cluster-b') == null
    }

    def 'resolve needs an array once the cluster has several'() {
        given:
        c15.setConfigProperty('pureLinks', [[storageServerId: 4, hostGroup: 'a']])

        expect:
        ctx.resolve(null).storageServerId == 4L
        ctx.resolve('4').storageServerId == 4L

        when:
        ctx.resolve(5)

        then:
        thrown(IllegalArgumentException)

        when:
        c15.setConfigProperty('pureLinks', [[storageServerId: 4, hostGroup: 'a'], [storageServerId: 7, hostGroup: 'b']])
        ctx.resolve(null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('several arrays')

        expect:
        ctx.resolve(7L).hostGroup == 'b'
    }

    def 'an unlinked cluster says so'() {
        when:
        ctx.resolve(null)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('not linked')
    }
}
