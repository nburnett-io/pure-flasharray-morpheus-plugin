package org.purehvm.morpheus

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Long-running operations (provision, grow, recovery) run off the HTTP request thread; the tab polls
 * /job?id=. One mutating job per cluster at a time. Running jobs are in memory; a finished job is also kept in the
 * cluster's config by {@link JobHistory} (through {@link #onFinish}), so the list survives a restart. A restart
 * mid-job still loses that job's progress view (not the array/host state, which every operation re-reads).
 */
@Slf4j
class Jobs {
    static class Job {
        String id
        String kind
        Long clusterId
        String user
        String state = 'running'
        List<String> progress = Collections.synchronizedList([])
        Object result
        String error
        long started = System.currentTimeMillis()
        Long ended
        Map meta = [:]   // e.g. instanceIds of a customer VM restore, so customers can poll only their own jobs

        Map toMap() {
            [id: id, kind: kind, clusterId: clusterId, user: user, state: state, progress: new ArrayList(progress),
             result: result, error: error, started: started, ended: ended]
        }
    }

    /** The cluster's one job slot is taken. An IllegalStateException, as callers that retry already catch that. */
    static class BusyException extends IllegalStateException {
        BusyException(String m) { super(m) }
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>()
    private static final Map<Long, String> RUNNING = new ConcurrentHashMap<>()
    private static final ExecutorService POOL = Executors.newCachedThreadPool()
    static final long KEEP_MS = 24L * 3600 * 1000
    /** Called with each finished job (set by the plugin: JobHistory.record). */
    static volatile Closure onFinish

    /** @param work receives the job's progress list and returns the result */
    static Job start(String kind, Long clusterId, String user, Closure work) {
        start(kind, clusterId, user, [:], work)
    }

    static Job start(String kind, Long clusterId, String user, Map meta, Closure work) {
        prune()
        Job j = new Job(id: UUID.randomUUID().toString(), kind: kind, clusterId: clusterId, user: user, meta: meta ?: [:])
        String other = RUNNING.putIfAbsent(clusterId, j.id)
        if (other != null) {
            throw new BusyException("Another Pure operation (${JOBS[other]?.kind ?: other.replaceFirst('^background:', 'background ')}) is running on this cluster")
        }
        JOBS[j.id] = j
        POOL.submit {
            try {
                j.result = work.call(j.progress)
                j.state = 'done'
            } catch (Throwable t) {
                log.error("Pure job ${kind} failed", t)
                j.error = t.message ?: t.class.simpleName
                j.state = 'failed'
            } finally {
                j.ended = System.currentTimeMillis()
                RUNNING.remove(clusterId, j.id)
                try {
                    onFinish?.call(j)
                } catch (Throwable t) {
                    log.warn("job ${kind}: finish hook failed: ${t.message}")
                }
            }
        }
        j
    }

    /**
     * Background work (the sweeper) that changes hosts or the array takes the same per-cluster slot as a job, on the
     * calling thread: it never runs beside a user's job, and a job started meanwhile is told what is running.
     * Returns false, without running {@code work}, when the cluster is busy.
     */
    static boolean tryExclusive(Long clusterId, String label, Closure work) {
        String token = "background:${label}".toString()
        if (RUNNING.putIfAbsent(clusterId, token) != null) {
            return false
        }
        try {
            work.call()
        } finally {
            RUNNING.remove(clusterId, token)
        }
        true
    }

    static boolean busy(Long clusterId) {
        RUNNING.containsKey(clusterId)
    }

    static Job get(String id) {
        JOBS[id]
    }

    static List<Job> forCluster(Long clusterId) {
        JOBS.values().findAll { it.clusterId == clusterId }.sort { -it.started }
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS
        JOBS.values().removeIf { it.ended && it.ended < cutoff }
    }
}
