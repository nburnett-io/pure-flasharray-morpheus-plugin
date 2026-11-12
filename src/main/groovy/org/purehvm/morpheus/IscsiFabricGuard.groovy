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
 * Duplicates are handled the same way: a second session to one portal over the same NIC through another iface record.
 * The iface the cluster's clean hosts use is kept ({@link #preferred}), so every host ends up alike.
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
        [found: all('FOUND'), removed: all('REMOVED'), kept: all('KEPT'), errors: all('ERROR'), warnings: all('WARN'), sessions: all('SESSION')]
    }

    /**
     * Which iface to keep where two sessions duplicate each other: the names the cluster's clean hosts (nothing found
     * on them) log in through, most used first. Empty when no host is clean; the host script then decides by itself.
     */
    static List<String> preferred(Collection<Map> dryRuns) {
        Map<String, Integer> votes = [:]
        dryRuns.findAll { !it.found && it.sessions }.each { Map p ->
            (p.sessions as List<String>).collect { it.tokenize('|')[1] }.findAll().unique().each { String i -> votes[i] = (votes[i] ?: 0) + 1 }
        }
        votes.keySet().sort { String a, String b -> votes[b] <=> votes[a] ?: a <=> b }
    }

    private static boolean usable(HostRunner.Result r) {
        r != null && (r.ok || r.output?.contains('PATHS='))
    }

    /** The read-only pass over every host: host name -> parsed result, hosts that could not be read left out. */
    static Map<String, Map> look(PureClusterContext ctx) {
        ctx.onAllHosts(HostScripts.removeCrossFabricSessions(true)).findAll { String h, HostRunner.Result r -> usable(r) }
                .collectEntries { String h, HostRunner.Result r -> [(h): parse(r)] } as Map<String, Map>
    }

    /** Removes what one host should not have (Remediate), keeping the iface the cluster's clean hosts use. */
    static Map removeOn(PureClusterContext ctx, String host) {
        parse(ctx.runner.run(host, HostScripts.removeCrossFabricSessions(false, preferred(look(ctx).values()))))
    }

    /** The alarm a host should carry after a pass, or null: [status, message]. */
    static Map alarmFor(String host, Map p) {
        List<String> left = (p.found + p.kept + p.errors) as List<String>
        if (!left) {
            return null
        }
        [status : p.errors ? 'error' : 'warning',
         message: "iSCSI sessions on ${host} that should not exist (wrong fabric, a gateway, or a duplicate): ${left.collect { it.replace('|', ' ') }.join('; ')}".toString() +
                 (p.found ? '. Not removed automatically (plugin setting off, or the removal did not run); Remediate on the host removes them.' : '')]
    }

    /** One pass over a cluster's hosts. A host that cannot be read keeps its alarm. Never throws per host. */
    static void run(MorpheusContext morpheus, PureClusterContext ctx, Map settings) {
        Map<String, Map> seen = look(ctx)
        List<String> dirty = seen.findAll { it.value.found }*.key
        if (autoRemove(settings) && dirty) {
            ctx.onAllHosts(HostScripts.removeCrossFabricSessions(false, preferred(seen.values())), dirty).each { String h, HostRunner.Result r ->
                if (usable(r)) {
                    seen[h] = parse(r)
                }
            }
        }
        ctx.hosts.each { ComputeServer s ->
            Map p = seen[s.name]
            if (p == null) {
                return
            }
            (p.removed as List<String>).each { String x ->
                log.warn("[${ctx.cluster.name}] ${s.name}: removed iSCSI session ${x}")
                Audit.warn(morpheus, null, ctx.cluster, 'removed a cross-fabric, routed or duplicate iSCSI session', [host: s.name, session: x])
            }
            ComplianceMonitor.setHostAlarm(morpheus, ctx.cluster.owner, s, "${CATEGORY}-${s.id}".toString(), CATEGORY,
                    "iSCSI sessions: ${s.name}".toString(), alarmFor(s.name, p))
        }
    }
}
