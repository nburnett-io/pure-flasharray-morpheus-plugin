package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import groovy.util.logging.Slf4j

/**
 * Lifetime of file-recovery copies (VmRestore.attachCopy): a copy is detached and deleted when its timer runs out, so
 * snapshot copies never linger on customer VMs. The user picks the lifetime when attaching (default 24 h, at most the
 * maximum, 72 h, both plugin settings) and can keep it longer, never beyond the maximum from now.
 *
 * The recovery sweeper calls {@link #sweep} every pass. For each copy on the cluster's GFS2 datastores:
 *  - timer run out: detach and delete, on the host where Morpheus has the VM
 *  - timer still running but not attached any more (the VM powered off, so the live-only copy left it): delete
 *  - the VM no longer exists in Morpheus: delete, once no cluster host has the domain defined
 * A copy without a timer (attached before 0.8.1, or its timer write failed) counts from its file time.
 */
@Slf4j
class FileRestoreCopies {
    static final long DEFAULT_HOURS = 24
    static final long MAX_HOURS = 72

    static long defaultHours(Map settings) {
        Math.min(hours(settings?.fileRestoreHours, DEFAULT_HOURS), maxHours(settings))
    }

    static long maxHours(Map settings) {
        hours(settings?.fileRestoreMaxHours, MAX_HOURS)
    }

    /** A positive whole number of hours from a setting, else {@code fallback}. */
    static long hours(Object v, long fallback) {
        String s = v as String
        s?.isLong() && (s as Long) > 0 ? s as Long : fallback
    }

    /** A requested lifetime, within 1 h and the maximum; the default when not given. */
    static long clamp(Object requested, Map settings) {
        String s = requested as String
        long h = s?.isLong() ? s as Long : defaultHours(settings)
        Math.max(1L, Math.min(h, maxHours(settings)))
    }

    /** Removal time of a copy: its timer (epoch ms), else its file time (epoch s) + the default lifetime. */
    static Long expiresOf(String timer, String mtimeSec, long defaultHours) {
        if (timer?.isLong()) {
            return timer as Long
        }
        mtimeSec?.isLong() ? (mtimeSec as Long) * 1000L + defaultHours * RecoverySessions.HOUR_MS : null
    }

    /** Domain (= Morpheus server externalId) a copy belongs to: its file name up to ".file-restore-". */
    static String domainOf(String file) {
        String n = file.substring(file.lastIndexOf('/') + 1)
        int i = n.indexOf(".${HostScripts.FILE_RESTORE_TAG}-")
        i > 0 ? n.substring(0, i) : null
    }

    /** One pass over the cluster's copies; returns counts. Never throws. */
    static Map sweep(MorpheusContext morpheus, PureClusterContext ctx, Map settings, long now = System.currentTimeMillis()) {
        Map stats = [removed: 0, kept: 0, skipped: 0, failed: 0]
        String first = ctx.hostNames.find()
        if (!first) {
            return stats
        }
        try {
            HostRunner.Result l = ctx.runner.run(first, HostScripts.fileRestoreSweepList())
            if (!l.ok) {
                log.warn("file-restore sweep on ${ctx.cluster.name}: ${l.error}")
                return stats
            }
            List<String> stale = l.all('STALE')
            if (stale) {
                ctx.runner.run(first, HostScripts.removeStaleTimers(stale))
            }
            long dflt = defaultHours(settings)
            l.all('CP').each { String line ->
                List<String> p = line.split(/\|/, 3) as List<String>
                String file = p[0], domain = domainOf(file), mount = SnapshotIndex.mountOf(file)
                Long expires = expiresOf(p[2] == '-' ? null : p[2], p[1], dflt)
                if (!domain || !mount) {
                    return
                }
                boolean expired = expires != null && expires <= now
                ComputeServer vm = vmOf(morpheus, ctx, domain)
                Map r
                if (vm == null) {
                    r = orphan(ctx, file, domain, mount)
                } else {
                    String host = ctx.hosts.find { it.id == vm.parentServer?.id }?.name
                    if (!host) {
                        stats.skipped += 1
                        return
                    }
                    r = run(ctx, host, HostScripts.fileRestoreRemove(file, domain, mount,
                            expired ? HostScripts.RemoveMode.REAP_EXPIRED : HostScripts.RemoveMode.REAP_IF_DETACHED))
                }
                if (r.removed) {
                    stats.removed += 1
                    if (vm != null) {   // Morpheus's sync imported the copy as a volume; drop that record now
                        VmRestore.dropCopyRecords(morpheus, vm, [], file.substring(file.lastIndexOf('/') + 1))
                    }
                    Audit.info(morpheus, null, ctx.cluster, 'file-restore copy removed automatically',
                            [vm: domain, copy: file.substring(file.lastIndexOf('/') + 1),
                             reason: vm == null ? 'VM no longer exists' : (expired ? "timer ran out at ${new Date(expires).toInstant()}" : 'no longer attached (VM powered off)')])
                } else if (r.kept) {
                    stats.kept += 1
                } else if (r.error) {
                    stats.failed += 1
                    log.warn("file-restore copy ${file}: ${r.error}")
                } else {
                    stats.skipped += 1
                }
            }
        } catch (Exception e) {
            log.warn("file-restore sweep on ${ctx.cluster.name} failed: ${e.message}", e)
        }
        if (stats.removed || stats.failed) {
            log.info("file-restore sweep ${ctx.cluster.name}: ${stats}")
        }
        stats
    }

    private static Map run(PureClusterContext ctx, String host, String script) {
        HostRunner.Result r = ctx.runner.run(host, script)
        [removed: r.kv.REMOVED != null, kept: r.kv.KEEP != null, error: r.ok ? null : r.error]
    }

    /** VM gone from Morpheus: delete only when no host of the cluster still has the domain defined. */
    private static Map orphan(PureClusterContext ctx, String file, String domain, String mount) {
        for (String h : ctx.hostNames) {
            if (ctx.runner.run(h, HostScripts.domainHere(domain)).kv.HERE == '1') {
                return [:]
            }
        }
        run(ctx, ctx.hostNames.find(), HostScripts.fileRestoreRemove(file, domain, mount, HostScripts.RemoveMode.ORPHAN))
    }

    private static ComputeServer vmOf(MorpheusContext morpheus, PureClusterContext ctx, String domain) {
        Set<Long> hostIds = ctx.hosts*.id as Set<Long>
        morpheus.services.computeServer.list(new DataQuery().withFilter('externalId', domain))
                .find { ComputeServer s -> hostIds.contains(s.parentServer?.id) }
    }
}
