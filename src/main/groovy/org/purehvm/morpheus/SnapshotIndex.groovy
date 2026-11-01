package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Datastore
import org.purehvm.api.FlashArrayClient
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import org.purehvm.ops.RecoveryOps
import groovy.util.logging.Slf4j

/**
 * Which files each array snapshot of a GFS2 datastore holds, so the Restore tab only offers restore points that
 * actually contain the VM (or which of its disks).
 *
 * What a snapshot holds can only be read by mounting it, but snapshots never change, so each is read once: the indexer
 * opens the snapshot read-only like any recovery session, lists the files, closes it, and writes the list to
 * <datastore>/.pure-hvm/index/<snapshot>.idx. The index lives on the datastore it describes: every host and appliance
 * node sees it, and it goes away with the datastore. Restores write the index of the snapshot they open for free.
 *
 * The recovery sweeper runs {@link #indexPending} after closing expired sessions, newest snapshots first, within a time
 * budget, and drops indexes of snapshots that no longer exist.
 */
@Slf4j
class SnapshotIndex {
    /** Time one sweeper pass may spend indexing, per cluster. */
    static final long PASS_BUDGET_MS = 6L * 60 * 1000
    /** Snapshots older than this are not indexed by the sweeper (the tab offers at most 50 points anyway). */
    static final int MAX_POINTS = 50

    static String fileName(String snapshot) {
        snapshot.replaceAll(/[^A-Za-z0-9._-]/, '_').take(196) + '.idx'
    }

    /** "/mnt/<uuid>" of a VM disk path, by HVM's GFS2 layout (every datastore mounts at /mnt/<uuid> on every host). */
    static String mountOf(String volumePath) {
        def m = volumePath =~ /^(\/mnt\/[^\/]+)\//
        m.find() ? m.group(1) : null
    }

    /** Writes the index of an open recovery session on the production datastore (best effort). */
    static boolean save(HostRunner runner, RecoveryOps.Session s, String prodMount) {
        try {
            HostRunner.Result l = runner.run(s.host, HostScripts.snapshotIndexList(s.mount))
            if (!l.ok) {
                log.warn("snapshot index of ${s.snapshot}: listing failed: ${l.error}")
                return false
            }
            HostRunner.Result w = runner.run(s.host, HostScripts.writeSnapshotIndex(prodMount, fileName(s.snapshot), l.all('IDXFILE')))
            if (!w.ok) {
                log.warn("snapshot index of ${s.snapshot}: write failed: ${w.error}")
            }
            w.ok
        } catch (Exception e) {
            log.warn("snapshot index of ${s.snapshot}: ${e.message}")
            false
        }
    }

    /**
     * Indexes the cluster's not-yet-indexed snapshots, newest first, until the budget is spent, and removes indexes of
     * snapshots that are gone. One snapshot at a time; each is a short read-only recovery session on the first host.
     */
    static Map indexPending(MorpheusContext morpheus, PureClusterContext ctx, long budgetMs = PASS_BUDGET_MS) {
        long deadline = System.currentTimeMillis() + budgetMs
        Map stats = [indexed: 0, pruned: 0, pending: 0]
        String host = ctx.hostNames.find()
        if (!host) {
            return stats
        }
        RetireOps retire = new RetireOps(morpheus, ctx)
        ctx.datastores.findAll { Datastore d -> (d.datastoreType?.code ?: '') =~ /(?i)gfs2/ }.each { Datastore ds ->
            Map arr = retire.arrayVolume(ds)
            if (arr.error || !arr.volume) {
                return
            }
            try {
                ctx.withArray(arr.storageServerId as Long) { FlashArrayClient fa ->
                    String pm = ctx.runner.run(host, HostScripts.findMount(arr.wwid as String)).kv.MOUNT
                    if (!pm) {
                        return
                    }
                    RecoveryOps ops = new RecoveryOps(fa, ctx.runner)
                    List<Map> all = ops.snapshots(arr.volume as String)
                    Set<String> have = ctx.runner.run(host, HostScripts.snapshotIndexNames(pm)).all('IDX') as Set<String>
                    List<String> stale = (have - (all.collect { fileName(it.name as String) } as Set<String>)) as List<String>
                    if (stale) {
                        ctx.runner.run(host, HostScripts.removeSnapshotIndexes(pm, stale))
                        stats.pruned += stale.size()
                    }
                    List<Map> todo = all.take(MAX_POINTS).findAll { !have.contains(fileName(it.name as String)) }
                    String arrayHost = todo ? ctx.arrayHostFor(fa, host) : null
                    for (Map snap : todo) {
                        if (System.currentTimeMillis() > deadline) {
                            stats.pending += 1
                            continue
                        }
                        RecoveryOps.Session s = ops.openRecovery(arr.volume as String, snap.name as String, host, arrayHost,
                                ctx.cluster.id, System.currentTimeMillis() + RecoverySessions.HOUR_MS)
                        try {
                            if (save(ctx.runner, s, pm)) {
                                stats.indexed += 1
                            }
                        } finally {
                            ops.closeRecovery(s)
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("snapshot index: ${ds.name} on ${ctx.cluster.name}: ${e.message}", e)
            }
        }
        if (stats.indexed || stats.pruned || stats.pending) {
            log.info("snapshot index: ${ctx.cluster.name}: ${stats}")
        }
        stats
    }
}
