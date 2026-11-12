package org.purehvm.morpheus

import org.purehvm.compliance.ComplianceChecker
import org.purehvm.compliance.HostFacts
import org.purehvm.compliance.Remediation
import org.purehvm.ops.HostRunner
import groovy.util.logging.Slf4j

/**
 * Applies host fixes on one cluster host: probe, pick, run one script per fix (Remediate, Add host and the automatic
 * join all go through here).
 *
 * Morpheus owns iSCSI on HVM 2.0: it builds the host's iSCSI networking and initiator name when it adds the host, and
 * logs every host in to each cluster iSCSI target with `hvmcli storage iscsi configure`. A host the plugin connects
 * gets exactly that: Morpheus's IQN if it has none, and hvmcli sessions to the cluster's Morpheus targets.
 */
@Slf4j
class HostConnect {
    /** Fixes applied without asking when a host joins (or Add host runs): all automatic ones except removing pools. */
    static final List<String> ON_JOIN = ['packages', 'iqn', 'services', 'multipath', 'udev', 'arp-sysctl', 'stale-iscsi-records', 'iscsi-sessions', 'node-startup']

    /**
     * The cluster's Morpheus iSCSI targets as Morpheus applied them: the array portals the cluster's other hosts are
     * logged in to. Null when no other host has a session yet (nothing to follow: every routed portal is wanted).
     */
    static List<String> appliedTargets(PureClusterContext ctx, String except, List<String> portals) {
        List<String> others = ctx.hostNames.findAll { it != except }
        if (!others) {
            return null
        }
        String script = '''set +e
iscsiadm -m session 2>/dev/null | awk '/purestorage/ {split($3,a,","); sub(/:[0-9]+$/,"",a[1]); print "PORTAL=" a[1]}'
'''
        Set<String> seen = [] as Set<String>
        ctx.onAllHosts(script, others).each { String h, HostRunner.Result r -> seen.addAll(r.all('PORTAL')) }
        List<String> mine = portals.findAll { seen.contains(it) }
        mine ? mine : null
    }

    /** Read-only: how each host reaches each portal (portals are array addresses, checked in ComplianceMonitor.arrayFacts). */
    static String routeScript(List<String> portals) {
        'set +e\nfor p in ' + portals.join(' ') + '; do r=$(ip -o route get "$p" 2>/dev/null); ' +
                'echo "ROUTE=$p|$(echo "$r" | sed -n \'s/.* dev \\([^ ]*\\).*/\\1/p\')|$(echo "$r" | sed -n \'s/.* via \\([^ ]*\\).*/\\1/p\')"; done\n'
    }

    /**
     * Per portal: the hosts that reach it directly (a storage path), only through a gateway, or not at all. Only a
     * portal every host reaches directly should become a Morpheus iSCSI target: Morpheus logs every host in to it.
     */
    static List<Map> portalReach(List<String> portals, Map<String, HostRunner.Result> byHost) {
        portals.collect { String p ->
            Map<String, List<String>> out = [direct: [], gatewayed: [], unreachable: []]
            byHost.each { String h, HostRunner.Result r ->
                List<String> row = r.all('ROUTE').collect { it.split(/\|/, -1) as List<String> }.find { it[0] == p }   // portal|dev|via
                String dev = row?.size() > 1 ? row[1] : null
                String via = row?.size() > 2 ? row[2] : null
                (dev && !via ? out.direct : (dev ? out.gatewayed : out.unreachable)) << h
            }
            [portal: p, direct: out.direct.sort(), gatewayed: out.gatewayed.sort(), unreachable: out.unreachable.sort(),
             everyHost: !byHost.isEmpty() && out.direct.size() == byHost.size()]
        }
    }

    /** True when {@code host} reaches at least one array portal directly, i.e. its iSCSI networking is up. */
    static boolean storageReady(PureClusterContext ctx, String host, List<String> portals) {
        if (!portals) {
            return false
        }
        Map<String, HostRunner.Result> res = ctx.onAllHosts(routeScript(portals), [host])
        portalReach(portals, res).any { (it.direct as List).contains(host) }
    }

    /**
     * Probes {@code host} and applies {@code fixes} (null: every FAILING check whose fix is in {@link #ON_JOIN}).
     * Returns the titles of the steps applied; throws on the first failed step, naming what was already applied.
     * @param morpheusTargets the cluster's Morpheus iSCSI target addresses, or null when unknown
     */
    static List<String> apply(PureClusterContext ctx, String host, Collection<String> fixes, Collection<String> morpheusTargets,
                              Map settings, List<String> progress) {
        Map info = ComplianceMonitor.arrayFacts(ctx)
        List<String> portals = info.portals as List<String>
        HostRunner r = ctx.runner
        HostFacts f = HostFacts.parse(r.run(host, ComplianceMonitor.probe(portals)).output)
        if (!f.complete) {
            throw new IllegalStateException("${host}: the probe did not finish; nothing changed")
        }
        f.gatewayed.each { p, via -> progress << "${host}: portal ${p} is only reachable through a gateway (${via}); left alone".toString() }
        String retry = Remediation.noPathRetrySetting(settings?.multipathNoPathRetry, ComplianceMonitor.allowZero(settings))
        Collection<String> chosen = fixes
        if (chosen == null) {
            ComplianceChecker.Context cc = new ComplianceChecker.Context(portals, info.iqns as Set<String>, info.purity as String, retry,
                    ComplianceMonitor.toleranceWarnSeconds(settings), ComplianceMonitor.allowZero(settings), ctx.storageMtu)
            chosen = new ComplianceChecker().check(f, cc).findAll { it.status == ComplianceChecker.Status.FAIL && it.fix in ON_JOIN }*.fix.unique()
        }
        if (!chosen) {
            progress << "${host}: iSCSI and multipath already set up".toString()
            return []
        }
        String raw = (settings?.multipathNoPathRetry as String)?.trim()
        if ('multipath' in chosen && raw && raw != retry) {
            progress << "${host}: the Multipath no_path_retry setting '${raw}' is not 1-60 (0 needs \"allow lowering to 0\"), so the default ${retry} is used".toString()
        }
        if ('iscsi-sessions' in chosen && f.hvmcli) {
            progress << (morpheusTargets == null
                    ? "${host}: no Morpheus iSCSI target applied on this cluster yet; connecting every array portal on this host's storage subnets"
                    : "${host}: following the cluster's Morpheus iSCSI targets ${morpheusTargets.join(', ')}").toString()
        }
        String recordIqn = ctx.hosts.find { it.name == host }?.getiScsiInitiatorName()
        List<Map<String, String>> steps = new Remediation().scriptSteps(f, chosen, portals, info.purity as String, retry,
                [iqn: recordIqn, targets: morpheusTargets, allowZero: ComplianceMonitor.allowZero(settings)])
        List<String> applied = []
        steps.eachWithIndex { Map<String, String> s, int i ->
            progress << "${host}: [${i + 1}/${steps.size()}] ${s.title}...".toString()
            HostRunner.Result res = r.run(host, s.script)
            (res.output ?: '').readLines().findAll { it.trim() && !it.startsWith('== ') && !it.startsWith('ERROR=') }
                    .each { progress << "    ${it}".toString() }
            if (!res.ok) {
                progress << "    ${res.error ?: ''}".toString()
                throw new IllegalStateException("${host}: step '${s.title}' failed (exit ${res.exitCode})" +
                        (applied ? "; already applied: ${applied.join(', ')}" : '; nothing applied before it'))
            }
            applied << s.title
        }
        applied
    }
}
