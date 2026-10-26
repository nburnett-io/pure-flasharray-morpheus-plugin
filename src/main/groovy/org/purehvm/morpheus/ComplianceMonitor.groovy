package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OperationNotification
import org.purehvm.api.FlashArrayClient
import org.purehvm.compliance.ComplianceChecker
import org.purehvm.compliance.HostFacts
import org.purehvm.ops.HostRunner
import org.purehvm.ops.ScriptWrapper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Host compliance: the read-only probe of every host against all linked arrays (the cluster tab's Check), and the
 * automatic run the recovery sweeper makes every "Automatic compliance check (hours)".
 *
 * The automatic run raises one Morpheus alarm per failing host (Operations > Alarms, on the host), naming the failing
 * checks, and clears it once the host passes. The alarm is this plugin's own (its uniqueId), so Morpheus's other alarms
 * on the host are never touched. A changed set of failures is also written to the audit log.
 */
@Slf4j
class ComplianceMonitor {
    static final String CATEGORY = 'pure-hvm-compliance'
    static final long DEFAULT_HOURS = 24

    /** clusterId -> the last automatic run: [at, hosts: [[host, failed, checks, error]]]. In memory: a reload runs it again. */
    static final Map<Long, Map> LAST = new ConcurrentHashMap<>()

    /** Hours between automatic runs; 0 (or less) turns them off. */
    static long intervalHours(Map settings) {
        String s = settings?.complianceCheckHours as String
        s?.isLong() ? Math.max(0L, s as Long) : DEFAULT_HOURS
    }

    // ------------------------------------------------------------------ the check

    /** Portals, IQNs and lowest Purity version across every linked array (compliance needs all of them). */
    static Map arrayFacts(PureClusterContext ctx) {
        List<Map> links = ctx.links
        if (!links) {
            throw new IllegalStateException("Cluster ${ctx.cluster.name} is not linked to a FlashArray yet")
        }
        List<String> portals = []
        Set<String> iqns = [] as Set
        List<String> versions = []
        links.each { Map l ->
            ctx.withArray(l.storageServerId as Long) { FlashArrayClient fa ->
                portals.addAll(fa.listIscsiPortals()*.address as List<String>)
                iqns.addAll(fa.listHosts().collectMany { (it.iqns ?: []) as List<String> })
                versions << (fa.array.version as String)
            }
        }
        portals = portals.unique()
        portals.each { if (!(it ==~ /[0-9a-fA-F.:]+/)) { throw new IllegalStateException("unexpected portal address ${it}") } }
        [portals: portals, iqns: iqns, purity: versions.findAll { it ==~ /\d+(\.\d+)*/ }.min { a, b -> FlashArrayClient.compareVersions(a, b) } ?: versions.find()]
    }

    /** The read-only host probe (compliance, remediation), run with the array portals as its arguments. */
    static String probe(List<String> portals) {
        ScriptWrapper.withArgs(ComplianceMonitor.getResourceAsStream('/scripts/pure-host-probe.sh').getText('UTF-8'), portals)
    }

    /** Probes every host (or only {@code only}) in parallel and checks it; one entry per host, as the tab shows it. */
    static List<Map> check(PureClusterContext ctx, String only = null) {
        Map info = arrayFacts(ctx)
        ComplianceChecker.Context cc = new ComplianceChecker.Context(info.portals as List<String>, info.iqns as Set<String>, info.purity as String)
        ctx.onAllHosts(probe(info.portals as List<String>), only ? [only] : ctx.hostNames).collect { String h, HostRunner.Result res ->
            hostView(h, res, cc)
        }
    }

    static Map hostView(String host, HostRunner.Result res, ComplianceChecker.Context cc) {
        HostFacts f = HostFacts.parse(res.output)
        List<ComplianceChecker.Result> rs = new ComplianceChecker().check(f, cc)
        [host: host, kernel: f.kernel, os: f.versionId, hvmcli: f.hvmcli,
         error: f.complete ? null : "the probe did not finish (${res.error ?: 'no output'})".toString(),
         failed: rs.count { it.status == ComplianceChecker.Status.FAIL },
         results: rs.collect { [id: it.id, title: it.title, severity: it.severity.name(), status: it.status.name(),
                                observed: it.observed, expected: it.expected, fix: it.fix, hint: it.hint] }]
    }

    // ------------------------------------------------------------------ automatic run + alarms

    /** The alarm a host should carry, or null when it passes: [status, message]. */
    static Map alarmFor(Map hostView) {
        if (hostView.error) {
            return [status: 'warning', message: "Pure FlashArray compliance check could not run: ${hostView.error}".toString()]
        }
        List<Map> failing = (hostView.results as List<Map>).findAll { it.status == 'FAIL' }
        if (!failing) {
            return null
        }
        List<String> names = failing.collect { "${it.id} ${it.title}".toString() }
        String list = names.size() > 5 ? names.take(5).join('; ') + "; and ${names.size() - 5} more" : names.join('; ')
        [status : failing.any { it.severity == 'HIGH' } ? 'error' : 'warning',
         message: "Pure FlashArray compliance: ${failing.size()} check(s) failing - ${list}. See the cluster's Pure FlashArray tab.".toString()]
    }

    static String uniqueId(Long hostId) {
        "${CATEGORY}-${hostId}".toString()
    }

    /** Runs the check on a cluster when due; never throws. */
    static void runIfDue(MorpheusContext morpheus, PureClusterContext ctx, Map settings, long now = System.currentTimeMillis()) {
        long hours = intervalHours(settings)
        Map last = LAST[ctx.cluster.id]
        if (hours <= 0 || (last?.at && now - (last.at as Long) < hours * RecoverySessions.HOUR_MS)) {
            return
        }
        try {
            run(morpheus, ctx, now)
        } catch (Exception e) {
            // arrays unreachable: try again next pass, not in a day
            log.warn("automatic compliance check of ${ctx.cluster.name} failed: ${e.message}")
        }
    }

    static Map run(MorpheusContext morpheus, PureClusterContext ctx, long now = System.currentTimeMillis()) {
        record(morpheus, ctx, check(ctx), now)
    }

    /**
     * Brings the alarms of the checked hosts in line with their results and remembers them. The tab's Check calls this
     * too, so a host fixed and checked again loses its alarm at once; a one-host check updates only that host.
     */
    static Map record(MorpheusContext morpheus, PureClusterContext ctx, List<Map> views, long now = System.currentTimeMillis()) {
        Map<String, ComputeServer> byName = ctx.hosts.collectEntries { [(it.name): it] }
        Map before = LAST[ctx.cluster.id]
        List<Map> hosts = views.collect { Map v ->
            Map a = alarmFor(v)
            ComputeServer s = byName[v.host as String]
            if (s) {
                setAlarm(morpheus, ctx, s, a)
            }
            List<String> checks = (v.results as List<Map>).findAll { it.status == 'FAIL' }*.id
            List<String> was = (before?.hosts as List<Map>)?.find { it.host == v.host }?.checks as List<String>
            if (was == null ? checks : was.toSet() != checks.toSet()) {
                Audit.warn(morpheus, null, ctx.cluster, checks ? 'automatic compliance check: host failing' : 'automatic compliance check: host passes again',
                        [host: v.host, failing: checks.join(',') ?: 'none', before: was == null ? null : (was.join(',') ?: 'none')])
            }
            [host: v.host, failed: checks.size(), checks: checks, error: v.error, alarm: a?.status, at: now]
        }
        // a one-host check keeps the other hosts' last results; the run time is that of the last full check
        boolean full = views*.host as Set == ctx.hostNames as Set
        List<Map> kept = ((before?.hosts ?: []) as List<Map>).findAll { Map h -> !hosts.any { it.host == h.host } && ctx.hostNames.contains(h.host) }
        Map out = [at: full ? now : before?.at, hosts: (kept + hosts).sort { it.host }]
        LAST[ctx.cluster.id] = out
        log.info("automatic compliance check of ${ctx.cluster.name}: ${hosts.collect { "${it.host}=${it.failed}" }.join(', ')}")
        out
    }

    /** Creates, replaces or clears this plugin's alarm on one host (an unchanged alarm is left as it is). */
    static void setAlarm(MorpheusContext morpheus, PureClusterContext ctx, ComputeServer host, Map alarm) {
        String uid = uniqueId(host.id)
        try {
            MorpheusSession.run(morpheus) {
                List<OperationNotification> open = morpheus.services.operationNotification.list(new DataQuery().withFilter('uniqueId', uid))
                        .findAll { it.active != false }
                if (alarm && open.size() == 1 && open[0].statusMessage == alarm.message && open[0].status == alarm.status) {
                    return
                }
                open.each { morpheus.services.operationNotification.clearAlarm(it) }
                if (alarm) {
                    def r = morpheus.services.operationNotification.createAlarm(new OperationNotification(
                            account: ctx.cluster.owner, category: CATEGORY, eventKey: CATEGORY, uniqueId: uid, name: "Pure FlashArray compliance: ${host.name}".toString(),
                            resourceName: host.name, refType: 'computeServer', refId: host.id, status: alarm.status as String,
                            statusMessage: alarm.message as String, startDate: new Date(), acknowledged: false, active: true))
                    if (r != null && !r.success) {
                        log.warn("compliance alarm on ${host.name} refused: ${r.msg ?: r.errors}")
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("compliance alarm on ${host.name} failed: ${e.message}", e)
        }
    }
}
