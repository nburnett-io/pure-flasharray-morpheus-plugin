package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import groovy.util.logging.Slf4j

/**
 * Runs a Morpheus core call inside a Hibernate session.
 *
 * The plugin's jobs run on their own threads, and some core services assume a web request's session is bound to the
 * thread. Seen on 9.1: MorpheusDatastoreTypeImplService.createDatastore and refreshDatastore call Datastore.get() and
 * fail with "Could not obtain current Hibernate Session" off a request thread. The plain data services
 * (morpheus.services.cloud.datastore.get/create/save/remove) manage their own sessions and do not need this.
 *
 * This reaches into Morpheus internals: it opens the session through GORM's static withNewSession on core's
 * com.morpheus.Datastore domain class, loaded from the classloader of the MorpheusContext implementation. If that class
 * is not there (a future Morpheus), the call runs as before.
 */
@Slf4j
class MorpheusSession {
    static final String DOMAIN_CLASS = 'com.morpheus.Datastore'

    static <T> T run(MorpheusContext morpheus, Closure<T> work) {
        Class domain = domainClass(morpheus)
        if (domain == null) {
            return work.call()
        }
        T out = null
        domain.withNewSession { session ->
            out = work.call()
            session.flush()
        }
        out
    }

    private static Class domainClass(MorpheusContext morpheus) {
        try {
            Class c = Class.forName(DOMAIN_CLASS, false, morpheus.getClass().classLoader)
            c.metaClass.respondsTo(c, 'withNewSession', Closure) || c.methods.any { it.name == 'withNewSession' } ? c : null
        } catch (Throwable e) {
            log.debug("no ${DOMAIN_CLASS} on the Morpheus classloader (${e.class.simpleName}); calling core without a new session")
            null
        }
    }
}
