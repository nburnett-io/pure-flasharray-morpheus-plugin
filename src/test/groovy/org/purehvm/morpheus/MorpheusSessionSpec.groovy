package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification

/**
 * 9.1 core's createDatastore/refreshDatastore need a Hibernate session on the calling thread; plugin jobs have none
 * ("Could not obtain current Hibernate Session"). MorpheusSession opens one through core's domain class.
 */
class MorpheusSessionSpec extends Specification {

    def setup() {
        com.morpheus.Datastore.sessions = 0
        com.morpheus.Datastore.flushes = 0
    }

    def 'core call runs inside a new session, which is flushed, and its result comes back'() {
        when:
        def out = MorpheusSession.run(Mock(MorpheusContext)) { 'created' }

        then:
        out == 'created'
        com.morpheus.Datastore.sessions == 1
        com.morpheus.Datastore.flushes == 1
    }

    def 'a failing core call propagates (the registrar then removes its record)'() {
        when:
        MorpheusSession.run(Mock(MorpheusContext)) { throw new IllegalStateException('boom') }

        then:
        def e = thrown(IllegalStateException)
        e.message == 'boom'
        com.morpheus.Datastore.flushes == 0
    }
}
