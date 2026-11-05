package org.purehvm.morpheus

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import groovy.util.logging.Slf4j

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An application-consistent array snapshot of one GFS2 datastore: freeze the guest filesystems of every running VM
 * with a disk on it (qemu guest agent, all hosts in parallel), take the snapshot, thaw, always. The array snapshot is
 * instant, so guests stay frozen for about as long as the host calls take. A VM without a guest agent cannot be
 * frozen: it is skipped and its disks are crash-consistent in this snapshot, as every disk is without freezing.
 *
 * Each frozen guest also has a host timer that thaws it after HostScripts.FREEZE_SAFETY_SEC, in case the plugin or
 * the appliance goes away between freeze and thaw.
 */
@Slf4j
class ConsistentSnapshot {
    final PureClusterContext ctx
    final List<String> progress

    /** The "Allow application-consistent snapshots" setting (off by default). */
    static boolean enabled(Map settings) {
        settings?.enableGuestFreeze in [true, 'on', 'true']
    }

    static void requireEnabled(Map settings) {
        if (!enabled(settings)) {
            throw new IllegalStateException('Application-consistent snapshots are turned off in the plugin settings ("Allow application-consistent snapshots")')
        }
    }

    ConsistentSnapshot(PureClusterContext ctx, List<String> progress) {
        this.ctx = ctx
        this.progress = progress
    }

    /** The datastore mount path (/mnt/<uuid>) of a cluster volume, from Morpheus's datastore records. */
    String mountOf(FlashArrayClient fa, String volume) {
        String serial = fa.getVolume(volume)?.serial as String
        String mp = HostOnboarding.clusterDatastores(ctx).find { PureNames.serialFromWwid(it.blockDevice)?.equalsIgnoreCase(serial ?: '-') }?.mountPath
        if (!mp) {
            throw new IllegalStateException("${volume} is not a mounted GFS2 datastore of this cluster in Morpheus: nothing to freeze")
        }
        mp
    }

    Map take(FlashArrayClient fa, String volume, String suffix) {
        RecoveryOps.requireSuffix(suffix)   // a bad suffix must fail before anything is frozen
        String mp = mountOf(fa, volume)
        String tag = Long.toString(System.currentTimeMillis(), 36)
        long t0 = System.currentTimeMillis()
        Map<String, List<String>> frozen = [:]
        List<String> skipped = []
        List<String> unknown = []
        String snapshot
        try {
            progress << "freezing the guests with a disk on ${mp} (every host in parallel)".toString()
            // every host at once (not onAllHosts' batches of 8): a guest frozen in an early batch could be thawed by
            // its timer before a later batch finished
            Map<String, HostRunner.Result> res = onEveryHostAtOnce(HostScripts.guestFreeze(mp, tag))
            unknown.addAll(ctx.hostNames.findAll { !res.containsKey(it) })   // the call itself failed
            res.each { String h, HostRunner.Result r ->
                frozen[h] = r.all('FROZEN')
                r.all('SKIPPED').each { String s -> skipped << "${s.replaceFirst(/\|/, ' on ' + h + ': ')}".toString() }
                if (!r.ok || !r.kv.DONE) {
                    unknown << h
                }
            }
            int n = frozen.values().sum(0) { it.size() } as int
            progress << "froze ${n} guest(s)${skipped ? '; not frozen (crash-consistent in this snapshot): ' + skipped.join('; ') : ''}".toString()
            unknown.each { progress << "WARNING ${it}: the freeze did not finish; its guests may be crash-consistent, and any it froze thaw within ${HostScripts.FREEZE_SAFETY_SEC} s".toString() }
            snapshot = new RecoveryOps(fa, ctx.runner, progress).snapshot(volume, suffix)
        } finally {
            thaw(frozen, tag)
        }
        int frozenCount = frozen.values().sum(0) { it.size() } as int
        long took = System.currentTimeMillis() - t0
        boolean inTime = took < HostScripts.FREEZE_SAFETY_SEC * 1000L
        if (!inTime) {
            progress << "WARNING: freeze to snapshot took ${(took / 1000) as long} s, longer than the ${HostScripts.FREEZE_SAFETY_SEC} s safety thaw; this snapshot may be crash-consistent for some guests".toString()
        }
        [snapshot: snapshot, frozen: frozenCount, skipped: skipped, consistent: !skipped && !unknown && inTime, seconds: (took / 1000) as long]
    }

    private Map<String, HostRunner.Result> onEveryHostAtOnce(String script) {
        List<String> hosts = ctx.hostNames
        if (!hosts) {
            return [:]
        }
        ExecutorService pool = Executors.newFixedThreadPool(hosts.size())
        try {
            Map<String, HostRunner.Result> out = new java.util.concurrent.ConcurrentHashMap<>()
            pool.invokeAll(hosts.collect { String h -> { -> out[h] = ctx.runner.run(h, script); null } as Callable<Object> })
            out
        } finally {
            pool.shutdown()
        }
    }

    /** Thaws every frozen guest, all hosts in parallel; one that cannot be thawed here is thawed by its host timer. */
    private void thaw(Map<String, List<String>> frozen, String tag) {
        Map<String, List<String>> todo = frozen.findAll { k, v -> v }
        if (!todo) {
            return
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(todo.size(), 16))
        try {
            List<String> lines = Collections.synchronizedList([])
            pool.invokeAll(todo.collect { String h, List<String> doms ->
                { ->
                    List<String> safe = doms.findAll { it ==~ /[A-Za-z0-9._:-]{1,128}/ }
                    (doms - safe).each { lines << "WARNING ${h}: guest '${it}' has an unusual name; its host timer thaws it".toString() }
                    if (safe) {
                        HostRunner.Result r = ctx.runner.run(h, HostScripts.guestThaw(safe, tag))
                        r.all('THAW_FAILED').each { lines << "WARNING ${h}: could not thaw ${it}; its host timer thaws it within ${HostScripts.FREEZE_SAFETY_SEC} s".toString() }
                        if (!r.ok) {
                            lines << "WARNING ${h}: thaw call failed (${r.error}); the host timers thaw its guests within ${HostScripts.FREEZE_SAFETY_SEC} s".toString()
                        }
                    }
                    null
                } as Callable<Object>
            })
            progress.addAll(lines)
            progress << "thawed the guests".toString()
        } finally {
            pool.shutdown()
        }
    }
}
