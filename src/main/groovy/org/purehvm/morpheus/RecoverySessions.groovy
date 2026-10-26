package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServerGroup
import org.purehvm.PureFlashArrayPlugin
import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Recovery sessions, with the ARRAY as the source of truth.
 *
 * A session is a snapshot clone tagged role=recovery-clone (source, snapshot, host, created, and since 0.5.7
 * recovery-cluster-id, tag, expires), connected privately to one host and mounted there under /mnt/pure-recover-<tag>.
 * The in-memory cache only saves host calls: after a plugin reload or appliance restart every session is rebuilt from
 * the tags (0.5.6 and earlier lost them, leaving clones mounted on a host for good).
 *
 * Sessions expire: tag "expires", else created + the plugin's "Recovery session timeout (hours)". The sweeper closes
 * expired sessions every 10 minutes, but never while another operation runs on that cluster (a VM restore could be
 * reading from the clone); it tries again on the next pass.
 */
@Slf4j
class RecoverySessions {
    static final long DEFAULT_TIMEOUT_HOURS = 8
    static final long HOUR_MS = 3600L * 1000
    static final String ROLE = 'recovery-clone'

    /** tag -> [clusterId, storageServerId, session, created, expires] */
    static final Map<String, Map> CACHE = new ConcurrentHashMap<>()

    static long timeoutHours(Map settings) {
        FileRestoreCopies.hours(settings?.recoveryTimeoutHours, DEFAULT_TIMEOUT_HOURS)
    }

    /** Expiry of a clone from its tags: explicit "expires", else created + timeout (null if neither is known). */
    static Long expiresAt(Map<String, String> tags, long timeoutHours) {
        if (tags.expires?.isLong()) {
            return tags.expires as Long
        }
        tags.created?.isLong() ? (tags.created as Long) + timeoutHours * HOUR_MS : null
    }

    /** True for a recovery clone of this cluster: tagged with its id, or (older clones) mounted on one of its hosts. */
    static boolean belongsTo(Map<String, String> tags, Long clusterId, Collection<String> hostNames) {
        tags.role == ROLE && (tags['recovery-cluster-id'] ? tags['recovery-cluster-id'] == clusterId as String : hostNames.contains(tags.host))
    }

    static String tagOf(String volume, Map<String, String> tags) {
        tags.tag ?: (volume.contains('-rcv-') ? volume.substring(volume.lastIndexOf('-rcv-') + 5) : volume)
    }

    /**
     * Rebuilds this cluster's sessions from every linked array. Cached sessions keep their file lists; new ones (after
     * a reload) get mount point and files from the host. Sessions whose clone is gone are dropped.
     */
    static List<Map> refresh(PureClusterContext ctx, long timeoutHours) {
        Set<String> seen = [] as Set<String>
        List<Map> out = []
        ctx.links.each { Map l ->
            Long sid = l.storageServerId as Long
            FlashArrayClient fa
            try {
                fa = ctx.client(sid)
            } catch (Exception e) {
                log.warn("recovery sessions: array ${sid} unreachable: ${e.message}")
                // keep what we know about that array rather than forgetting live sessions
                CACHE.values().findAll { it.clusterId == ctx.cluster.id && it.storageServerId == sid }.each { seen << (it.session as RecoveryOps.Session).tag; out << it }
                return
            }
            try {
                Map<String, Map<String, String>> byVol = fa.tagsByVolume()
                List<String> mine = byVol.findAll { k, v -> belongsTo(v, ctx.cluster.id, ctx.hostNames) }*.key
                if (!mine) {
                    return
                }
                // a closed clone whose eradication was refused keeps its tags in the destroyed bucket; naming it errors
                fa.listVolumes([destroyed: false]).findAll { mine.contains(it.name) }.each { Map v ->
                    Map<String, String> tags = byVol[v.name as String]
                    String tag = tagOf(v.name as String, tags)
                    seen << tag
                    Map entry = CACHE[tag]
                    if (!entry) {
                        entry = [clusterId: ctx.cluster.id, storageServerId: sid, session: rebuild(ctx, fa, v, tags, tag)]
                        CACHE[tag] = entry
                        log.info("recovery session ${tag} (${v.name}) rebuilt from the array")
                    }
                    entry.created = tags.created?.isLong() ? tags.created as Long : null
                    entry.expires = expiresAt(tags, timeoutHours)
                    out << entry
                }
            } finally {
                fa.close()
            }
        }
        CACHE.entrySet().removeIf { it.value.clusterId == ctx.cluster.id && !seen.contains(it.key) }
        out
    }

    private static RecoveryOps.Session rebuild(PureClusterContext ctx, FlashArrayClient fa, Map v, Map<String, String> tags, String tag) {
        RecoveryOps.Session s = new RecoveryOps.Session(tag: tag, volume: v.name, serial: v.serial, wwid: PureNames.wwid(v.serial as String),
                host: tags.host, sourceVolume: tags.source, snapshot: tags.snapshot)
        s.arrayHost = fa.listConnections([volume_names: v.name]).find { it.host && !it.hostGroup }?.host
        if (s.host && ctx.hostNames.contains(s.host)) {
            HostRunner.Result m = ctx.runner.run(s.host, HostScripts.findMount(s.wwid))
            s.mount = m.kv.MOUNT ?: null
            if (s.mount) {
                s.files = RecoveryOps.parseFiles(ctx.runner.run(s.host, HostScripts.recoveryFiles(s.mount)))
            }
        }
        s
    }

    /** Pushes a session's expiry out by one timeout from now ("Keep open"). */
    static long extend(FlashArrayClient fa, Map entry, long timeoutHours) {
        long until = System.currentTimeMillis() + timeoutHours * HOUR_MS
        fa.tagVolume((entry.session as RecoveryOps.Session).volume, PureNames.tagNamespace, [expires: until as String])
        entry.expires = until
        until
    }

    // ------------------------------------------------------------------ sweeper

    private static ScheduledExecutorService sweeper

    static synchronized void startSweeper(MorpheusContext morpheus, Plugin plugin) {
        stopSweeper()
        sweeper = Executors.newSingleThreadScheduledExecutor { Runnable r ->
            Thread t = new Thread(r, 'pure-hvm-recovery-sweeper')
            t.daemon = true
            t
        }
        sweeper.scheduleWithFixedDelay({
            try {
                sweep(morpheus, plugin)
            } catch (Throwable e) {
                log.warn("recovery sweeper pass failed: ${e.message}", e)
            }
        } as Runnable, 2, 10, TimeUnit.MINUTES)
    }

    static synchronized void stopSweeper() {
        sweeper?.shutdownNow()
        sweeper = null
    }

    /**
     * One pass per linked cluster: close expired sessions (skipping busy clusters), remove file-recovery copies whose
     * time is up or that left their VM (FileRestoreCopies), index snapshots the Restore tab has not seen yet
     * (SnapshotIndex), and run the host compliance check when it is due (ComplianceMonitor).
     */
    static void sweep(MorpheusContext morpheus, Plugin plugin) {
        Map settings = PureFlashArrayPlugin.readSettings(morpheus, plugin)
        long hours = timeoutHours(settings)
        long now = System.currentTimeMillis()
        morpheus.services.cluster.list(new DataQuery()).findAll { ComputeServerGroup c -> PureClusterContext.linksOf(c) }.each { ComputeServerGroup c ->
            PureClusterContext ctx = new PureClusterContext(morpheus, c.id)
            closeExpired(morpheus, ctx, c, refresh(ctx, hours).findAll { it.expires && it.expires < now })
            FileRestoreCopies.sweep(morpheus, ctx, settings)
            try {
                SnapshotIndex.indexPending(morpheus, ctx)
            } catch (Exception e) {
                log.warn("snapshot index pass on ${c.name} failed: ${e.message}", e)
            }
            ComplianceMonitor.runIfDue(morpheus, ctx, settings, now)
        }
    }

    private static void closeExpired(MorpheusContext morpheus, PureClusterContext ctx, ComputeServerGroup c, List<Map> expired) {
        if (!expired) {
            return
        }
        try {
            Jobs.start('recovery-timeout', c.id, 'auto-timeout') { List<String> progress ->
                expired.collect { Map e ->
                    RecoveryOps.Session s = e.session as RecoveryOps.Session
                    progress << "${s.tag}: ${s.snapshot} on ${s.host} expired at ${new Date(e.expires as Long).toInstant()} - closing".toString()
                    ctx.withArray(e.storageServerId as Long) { FlashArrayClient fa ->
                        new RecoveryOps(fa, ctx.runner, progress).closeRecovery(s)
                        CACHE.remove(s.tag)
                        Audit.info(morpheus, null, c, 'recovery session closed by timeout',
                                [session: s.tag, snapshot: s.snapshot, host: s.host, expired: new Date(e.expires as Long).toInstant()])
                        s.tag
                    }
                }
            }
            log.info("recovery sweeper: closing ${expired.size()} expired session(s) on ${c.name}")
        } catch (IllegalStateException busy) {
            log.info("recovery sweeper: ${c.name} is busy (${busy.message}); ${expired.size()} expired session(s) wait for the next pass")
        }
    }
}
