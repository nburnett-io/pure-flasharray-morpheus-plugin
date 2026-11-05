package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/**
 * The last few finished jobs of a cluster, kept in the cluster's own config (key pureJobs) so the Operations list
 * survives a plugin reload or an appliance restart. Plugin API 1.5.0 has no process history for a cluster or a storage
 * server and no key-value store, so the cluster config, which also holds the array links, is the place.
 * Each entry is small (the end of the log only) and the list is capped.
 */
@Slf4j
class JobHistory {
    static final String CFG = 'pureJobs'
    static final int KEEP = 15
    static final int LOG_LINES = 12
    static final int LINE_CHARS = 300
    /** Every write of the cluster config by the plugin (job history, links) holds this: each save writes the whole config. */
    static final Object LOCK = new Object()

    static Map summary(Jobs.Job j) {
        List<String> lines = new ArrayList<String>(j.progress)
        [id   : j.id, kind: j.kind, user: j.user, state: j.state, started: j.started, ended: j.ended,
         error: trim(j.error), progress: lines.takeRight(LOG_LINES).collect { trim(it) }, truncated: lines.size() > LOG_LINES, persisted: true]
    }

    private static String trim(String s) {
        s == null ? null : (s.length() > LINE_CHARS ? s.substring(0, LINE_CHARS) + '...' : s)
    }

    /** Newest first, as stored. */
    static List<Map> list(ComputeServerGroup c) {
        Object v = c?.getConfigProperty(CFG)
        v instanceof List ? (v as List).findAll { it instanceof Map && it.id } as List<Map> : []
    }

    /**
     * Adds a finished job. Reads the cluster again first: the save writes the whole config, and a stale copy would undo
     * a link change made meanwhile. Never throws (history is a convenience).
     */
    static void record(MorpheusContext morpheus, Jobs.Job j) {
        if (!j?.clusterId) {
            return
        }
        synchronized (LOCK) {
            try {
                ComputeServerGroup c = morpheus.services.cluster.get(j.clusterId)
                if (!c) {
                    return
                }
                List<Map> kept = ([summary(j)] + list(c).findAll { it.id != j.id }).take(KEEP)
                c.setConfigProperty(CFG, kept)
                morpheus.services.cluster.save(c)
            } catch (Exception e) {
                log.warn("job history for cluster ${j.clusterId} not saved: ${e.message}")
            }
        }
    }

    /** In-memory jobs first (they have the full log), then stored ones not in memory; newest first. */
    static List<Map> merged(List<Jobs.Job> live, ComputeServerGroup c, int max) {
        Set<String> ids = live*.id as Set<String>
        (live*.toMap() + list(c).findAll { !ids.contains(it.id) }).sort { -((it.started ?: 0L) as Long) }.take(max)
    }
}
