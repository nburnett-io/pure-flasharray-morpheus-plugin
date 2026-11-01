package com.morpheus

/** Test stand-in for core's GORM domain class: only the static withNewSession that MorpheusSession calls. */
class Datastore {
    static int sessions = 0
    static int flushes = 0

    static Object withNewSession(Closure work) {
        sessions++
        work.call([flush: { flushes++ }] as Object)
    }
}
