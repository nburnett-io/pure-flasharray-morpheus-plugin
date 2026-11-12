package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import groovy.util.logging.Slf4j

/**
 * iSCSI is never routed and never crosses fabrics. On every sweeper pass each host's FlashArray sessions are checked
 * (HostScripts.removeCrossFabricSessions); with the setting on (default), a session through a gateway or through the
 * other fabric's NIC is logged out and its record deleted, under the script's path guards. What could not be removed
 * (or everything found, with the setting off) stays as a warning alarm on the host; removals go to the audit log.
 *
 * Seen in the field: a host with per-NIC iface binding logged each NIC in to all four portals after a datastore was
 * added (the array answers ARP for every portal address on every port), doubling the sessions with crossed ones.
 */
@Slf4j
class IscsiFabricGuard {
    static final String CATEGORY = 'pure-hvm-iscsi-fabric'

    /** The "remove cross-fabric and routed sessions automatically" setting: on unless switched off. */
    static boolean autoRemove(Map settings) {
        !(settings?.autoRemoveCrossFabric in [false, 'off', 'false'])
    }

    static Map parse(HostRunner.Result r) {
        List<String> lines = r.output?.readLines() ?: []
        Closure<List<String>> all = { String k -> lines.findAll { it.startsWith(k + '=') }.collect { it.substring(k.length() + 1) } }
        [found: all('FOUND'), removed: all('REMOVED'), kept: all('KEPT'), errors: all('ERROR'), warnings: all('WARN')]
    }

    /** The alarm a host should carry after a pass, or null: [status, message]. */
    static Map alarmFor(String host, Map p) {
        List<String> left = (p.found + p.kept + p.errors) as List<String>
        if (!left) {
            return null
        }
        [status : p.errors ? 'error' : 'warning',
         message: "iSCSI sessions on ${host} that go through the wrong fabric or a gateway: ${left.collect { it.replace('|', ' ') }.join('; ')}".toString() +
                 (p.found ? '. Automatic removal is off (plugin setting); Remediate on the host removes them.' : '')]
    }

    /** One pass over a cluster's hosts. A host that cannot be read keeps its alarm. Never throws per host. */
    static void run(MorpheusContext morpheus, PureClusterContext ctx, Map settings) {
        boolean remove = autoRemove(settings)
        Map<String, HostRunner.Result> res = ctx.onAllHosts(HostScripts.removeCrossFabricSessions(!remove))
        ctx.hosts.each { ComputeServer s ->
            HostRunner.Result r = res[s.name]
            if (r == null || (!r.ok && !r.output?.contains('PATHS='))) {
                return
            }
            Map p = parse(r)
            (p.removed as List<String>).each { String x ->
                log.warn("[${ctx.cluster.name}] ${s.name}: removed iSCSI session ${x}")
                Audit.warn(morpheus, null, ctx.cluster, 'removed a cross-fabric or routed iSCSI session', [host: s.name, session: x])
            }
            ComplianceMonitor.setHostAlarm(morpheus, ctx.cluster.owner, s, "${CATEGORY}-${s.id}".toString(), CATEGORY,
                    "iSCSI across fabrics: ${s.name}".toString(), alarmFor(s.name, p))
        }
    }
}
