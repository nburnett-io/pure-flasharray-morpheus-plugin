package org.purehvm.morpheus

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames
import groovy.util.logging.Slf4j

/**
 * The array's snapshot policies (protection groups) over this cluster's datastores, READ ONLY: schedules, retention,
 * replication and SafeMode are set on the array by the storage team, never from Morpheus.
 *
 * A datastore volume is protected by a protection group that holds it directly, or holds a host group or host the
 * volume is connected to (Purity snapshots every volume connected to a member host or host group).
 */
@Slf4j
class SnapshotPolicies {
    static final long DAY_S = 86400

    /** Per linked array: its SafeMode setting and, per datastore volume of this cluster, the policies that cover it. */
    static List<Map> forCluster(PureClusterContext ctx) {
        ctx.links.collect { Map l ->
            Long sid = l.storageServerId as Long
            try {
                ctx.withArray(sid) { FlashArrayClient fa ->
                    Map<String, Map<String, String>> byVol = fa.tagsByVolume()
                    List<String> mine = byVol.findAll { k, v -> PureNames.ownedBy(v, ctx.cluster.id) }*.key
                    // tags outlive destroyed volumes: only live ones
                    List<String> live = mine ? fa.listVolumes([destroyed: false])*.name.findAll { mine.contains(it) } as List<String> : []
                    [storageServerId: sid, server: ctx.storageServer(sid).name, safeMode: safeMode(fa.array),
                     volumes: forVolumes(fa, live).collect { it + [datastore: byVol[it.volume as String]?.get('datastore-name')] }]
                }
            } catch (Exception e) {
                log.warn("snapshot policies of array ${sid} for ${ctx.cluster.name}: ${e.message}")
                [storageServerId: sid, error: e.message]
            }
        }
    }

    /** Per volume: [volume, policies (see describe, plus via and lastSnapshot), scheduled]. */
    static List<Map> forVolumes(FlashArrayClient fa, List<String> volumes) {
        if (!volumes) {
            return []
        }
        Map<String, Map> groups = fa.listProtectionGroups().collectEntries { [(it.name as String): it] }
        Map<String, List<String>> byVolume = fa.protectionGroupsByMember('volumes')
        Map<String, List<String>> byHostGroup = fa.protectionGroupsByMember('host-groups')
        Map<String, List<String>> byHost = fa.protectionGroupsByMember('hosts')
        List<Map> conns = fa.listConnections([volume_names: volumes])
        Map<String, Long> last = [:]
        volumes.collect { String v ->
            List<Map> via = (byVolume[v] ?: []).collect { [group: it, via: 'volume'] }
            conns.findAll { it.volume == v && it.hostGroup }*.hostGroup.unique().each { String hg ->
                (byHostGroup[hg] ?: []).each { via << [group: it, via: "host group ${hg}".toString()] }
            }
            conns.findAll { it.volume == v && it.host }*.host.unique().each { String h ->
                (byHost[h] ?: []).each { via << [group: it, via: "host ${h}".toString()] }
            }
            List<Map> policies = via.groupBy { it.group }.findResults { String g, List<Map> rows ->
                Map pg = groups[g]
                if (pg == null) {
                    return null   // destroyed, or not on this array
                }
                if (!last.containsKey(g)) {
                    last[g] = latestCreated(fa, g)
                }
                describe(pg) + [via: rows*.via.unique(), lastSnapshot: last[g]]
            } as List<Map>
            [volume: v, policies: policies, scheduled: policies.any { it.scheduled }]
        }
    }

    private static Long latestCreated(FlashArrayClient fa, String group) {
        try {
            fa.latestProtectionGroupSnapshot(group)?.created as Long
        } catch (Exception e) {
            log.debug("latest snapshot of ${group}: ${e.message}")
            null
        }
    }

    /** One protection group as the tab shows it. */
    static Map describe(Map pg) {
        Map sch = (pg.snapshot_schedule ?: [:]) as Map
        Map ret = (pg.source_retention ?: [:]) as Map
        Map rep = (pg.replication_schedule ?: [:]) as Map
        Long every = ((sch.frequency ?: 0) as Long).intdiv(1000)
        boolean scheduled = sch.enabled == true && every > 0
        [name       : pg.name, scheduled: scheduled, everySeconds: scheduled ? every : null, every: scheduled ? duration(every) : null,
         retention  : retention(ret), keptSeconds: keptSeconds(ret),
         replication: rep.enabled == true && rep.frequency ? "every ${duration((rep.frequency as Long).intdiv(1000))}".toString() : null,
         safeMode   : pg.retention_lock == 'ratcheted', retentionLock: pg.retention_lock]
    }

    /** "all for 1 day, then 4 per day for 7 days" from a Purity retention block. */
    static String retention(Map r) {
        Long all = r.all_for_sec as Long
        Long perDay = r.per_day as Long
        Long days = r.days as Long
        List<String> parts = []
        if (all) {
            parts << "all for ${duration(all)}".toString()
        }
        if (perDay && days) {
            parts << "${perDay} per day for ${days} more day${days == 1 ? '' : 's'}".toString()
        }
        parts ? parts.join(', then ') : 'not reported'
    }

    /** How far back the retention reaches: everything for all_for_sec, then per-day ones for days more. */
    static Long keptSeconds(Map r) {
        Long all = (r.all_for_sec ?: 0) as Long
        Long days = (r.per_day ? (r.days ?: 0) : 0) as Long
        all + days * DAY_S ?: null
    }

    static String duration(long seconds) {
        [[DAY_S, 'day'], [3600L, 'hour'], [60L, 'minute']].findResult { List u ->
            long n = seconds.intdiv(u[0] as long)
            seconds >= (u[0] as long) && seconds % (u[0] as long) == 0 ? "${n} ${u[1]}${n == 1 ? '' : 's'}".toString() : null
        } ?: "${seconds} seconds".toString()
    }

    /**
     * Array-wide SafeMode (Purity 6.4.10+ reports eradication_config): manual eradication disabled means destroyed
     * volumes and snapshots cannot be eradicated before the delay runs out.
     */
    static Map safeMode(Map array) {
        Map ec = array?.eradication_config as Map
        if (!ec) {
            return [reported: false]
        }
        String manual = ec.manual_eradication as String
        [reported: true, on: manual != null && manual.toLowerCase().contains('disabled'), manualEradication: manual,
         eradicationDelay: ec.eradication_delay ? duration((ec.eradication_delay as Long).intdiv(1000)) : null]
    }

    /**
     * The one line a customer sees on the Restore tab: how often restore points are taken and how long they are kept,
     * with no protection-group, array or datastore names.
     */
    static String customerText(List<Map> policies) {
        List<Map> s = (policies ?: []).findAll { it.scheduled }
        if (!s) {
            return 'Restore points are taken on demand by your provider.'
        }
        Map often = s.min { it.everySeconds as Long }
        Long longest = s*.keptSeconds.findAll().max() as Long
        "Restore points are taken every ${often.every}${longest ? " and kept for up to ${duration(longest)}" : ''}.".toString()
    }
}
