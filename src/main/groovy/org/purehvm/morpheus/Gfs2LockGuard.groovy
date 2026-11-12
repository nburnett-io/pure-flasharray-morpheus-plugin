package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Guard against a cluster GFS2 mounted with lock_nolock. With no cluster locking that host allocates blocks and uses a
 * journal without telling the others; if any other host mounts the filesystem too (with lock_dlm or lock_nolock), the
 * filesystem is corrupted (double-allocated blocks, a journal written by two hosts). Seen in the field: a manual
 * lock_nolock mount left in place for a week while the other hosts mounted it normally.
 *
 * Runs on every sweeper pass. A host with such a mount carries an error alarm (Operations > Alarms, on the host) until
 * the mount is gone; each change is written to the audit log. The plugin's own recovery clones (lock table recover:*,
 * on a cloned LUN only one host sees) are the one lock_nolock mount that is expected.
 */
@Slf4j
class Gfs2LockGuard {
    static final String CATEGORY = 'pure-hvm-gfs2-lock'
    static final String RECOVERY_TABLE_PREFIX = 'recover:'

    /** clusterId -> host -> the finding last reported (null = clean). In memory: a reload reports open findings again. */
    static final Map<Long, Map<String, String>> LAST = new ConcurrentHashMap<>()

    static List<Map> parse(HostRunner.Result r) {
        r.all('GFS2').collect { String l ->
            List<String> f = l.split(/\|/, -1) as List<String>
            [table: f[0], proto: f.size() > 1 ? f[1] : '', mount: f.size() > 2 ? f[2] : '']
        }
    }

    /**
     * The plugin's own single-host lock_nolock mounts of a CLONED LUN: recovery sessions (lock table recover:*, at
     * /mnt/pure-recover-*) and the few seconds of journal replay when a snapshot copy is re-signed (/mnt/pure-resign-*,
     * lock table already changed).
     */
    static boolean pluginOwn(Map m) {
        (m.table as String).startsWith(RECOVERY_TABLE_PREFIX) || (m.mount as String) ==~ /\/mnt\/pure-(recover|resign)-[A-Za-z0-9]+/
    }

    /** host -> what is wrong there, for every host with a lock_nolock mount that is not one of the plugin's own. */
    static Map<String, String> findings(Map<String, List<Map>> mountsByHost) {
        Map<String, String> out = [:]
        mountsByHost.each { String h, List<Map> mounts ->
            List<String> bad = mounts.findAll { it.proto == 'lock_nolock' && !pluginOwn(it) }.collect { Map m ->
                List<String> others = mountsByHost.findAll { String oh, List<Map> om -> oh != h && om.any { it.table == m.table } }*.key.sort()
                "${m.mount ?: '?'} (lock table ${m.table}) is mounted with lock_nolock, without cluster locking".toString() +
                        (others ? "; ${others.join(', ')} mount${others.size() == 1 ? 's' : ''} it at the same time: two hosts are writing it uncoordinated, which corrupts GFS2. Stop the VMs using it on ${h} and unmount it there now".toString()
                                : "; no other host may mount it until it is unmounted on ${h}".toString())
            }
            if (bad) {
                out[h] = bad.join('; ')
            }
        }
        out
    }

    /** One pass over a cluster's hosts: alarms and audit follow the findings. A host that cannot be read keeps its alarm. */
    static Map<String, String> run(MorpheusContext morpheus, PureClusterContext ctx) {
        Map<String, HostRunner.Result> res = ctx.onAllHosts(HostScripts.gfs2Locks())
        Map<String, List<Map>> mounts = res.findAll { h, r -> r.ok }.collectEntries { h, r -> [(h): parse(r)] } as Map<String, List<Map>>
        Map<String, String> found = findings(mounts)
        Map<String, String> before = LAST[ctx.cluster.id] ?: [:]
        Map<String, String> now = [:]
        ctx.hosts.each { ComputeServer s ->
            if (!res[s.name]?.ok) {
                if (before.containsKey(s.name)) {
                    now[s.name] = before[s.name]
                }
                return
            }
            String msg = found[s.name]
            now[s.name] = msg
            ComplianceMonitor.setHostAlarm(morpheus, ctx.cluster.owner, s, "${CATEGORY}-${s.id}".toString(), CATEGORY,
                    "GFS2 without cluster locking: ${s.name}".toString(),
                    msg ? [status: 'error', message: "GFS2 mounted with lock_nolock on ${s.name}: ${msg}".toString()] : null)
            if (msg != before[s.name] && (msg || before[s.name])) {
                if (msg) {
                    log.error("[${ctx.cluster.name}] ${s.name}: ${msg}")
                } else {
                    log.info("[${ctx.cluster.name}] ${s.name}: lock_nolock GFS2 mount gone")
                }
                Audit.warn(morpheus, null, ctx.cluster, msg ? 'GFS2 mounted with lock_nolock' : 'GFS2 lock_nolock mount gone', [host: s.name, detail: msg ?: 'none'])
            }
        }
        LAST[ctx.cluster.id] = now
        found
    }
}
