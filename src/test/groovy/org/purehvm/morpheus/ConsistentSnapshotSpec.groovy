package org.purehvm.morpheus

import spock.lang.Specification

class ConsistentSnapshotSpec extends Specification {
    def 'guest freeze is off unless the setting turns it on'() {
        expect:
        !ConsistentSnapshot.enabled(null)
        !ConsistentSnapshot.enabled([:])
        !ConsistentSnapshot.enabled([enableGuestFreeze: 'off'])
        ConsistentSnapshot.enabled([enableGuestFreeze: 'on'])
        ConsistentSnapshot.enabled([enableGuestFreeze: true])

        when:
        ConsistentSnapshot.requireEnabled([:])

        then:
        IllegalStateException e = thrown()
        e.message.contains('turned off')
    }

    def 'the workflow task reads its options by code'() {
        given:
        def task = new com.morpheusdata.model.Task(taskOptions: [
                new com.morpheusdata.model.TaskOption(optionType: new com.morpheusdata.model.OptionType(code: PureSnapshotTaskProvider.OPT_FREEZE), value: 'on')])

        expect:
        PureSnapshotTaskProvider.option(task, PureSnapshotTaskProvider.OPT_FREEZE) == 'on'
        PureSnapshotTaskProvider.option(task, PureSnapshotTaskProvider.OPT_SUFFIX) == null
    }
    def 'the task reads the running user tenant from the object core passes, and refuses when it cannot'() {
        given: 'core passes its own user object, not the plugin model'
        def coreUser = new Expando(account: new Expando(id: 7L, masterAccount: false))
        def cluster = new com.morpheusdata.model.ComputeServerGroup(owner: new com.morpheusdata.model.Account(id: 7L))
        def other = new com.morpheusdata.model.ComputeServerGroup(owner: new com.morpheusdata.model.Account(id: 9L))

        expect:
        PureSnapshotTaskProvider.runnerOf([user: coreUser]).id == 7L
        Tenancy.isOperatorTenant(PureSnapshotTaskProvider.runnerOf([user: coreUser]), cluster)
        !Tenancy.isOperatorTenant(PureSnapshotTaskProvider.runnerOf([user: coreUser]), other)
        PureSnapshotTaskProvider.runnerOf([:]) == null
        PureSnapshotTaskProvider.runnerOf([user: 'not a user']) == null
        !Tenancy.isOperatorTenant(PureSnapshotTaskProvider.runnerOf([:]), cluster)
    }
}
