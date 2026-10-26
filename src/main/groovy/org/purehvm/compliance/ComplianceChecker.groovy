package org.purehvm.compliance

import org.purehvm.ops.HostScripts
import groovy.transform.Canonical

/**
 * Evaluates {@link HostFacts} against Pure's Linux/iSCSI recommendations as they apply to HVM GFS2 clusters.
 * Check ids and expected values come from docs/research/07-gfs2-clone-and-linux-best-practice.md (table B2), which cites
 * the Everpure Linux Recommended Settings, the Everpure HPE VME iSCSI/FC guides and Morpheus 9.1 docs.
 *
 * Where the vendor pages disagree, the HVM requirement wins: user_friendly_names must be "no" because Morpheus
 * addresses GFS2 LUNs as /dev/mapper/3<wwid> on every host.
 */
class ComplianceChecker {

    static enum Severity { HIGH, MEDIUM, LOW }
    static enum Status { PASS, FAIL, WARN, NA }

    @Canonical
    static class Result {
        String id
        String title
        Severity severity
        Status status
        String observed
        String expected
        /** Remediation key understood by {@link Remediation}, or null when the fix is manual. */
        String fix
        /** For a manual fix: how to do it on this host. */
        String hint
    }

    @Canonical
    static class Context {
        List<String> portals = []          // array iSCSI portal addresses
        Set<String> registeredIqns = []    // IQNs present on the array's host objects
        String purityVersion               // e.g. 6.10.4
    }

    static final Map<String, String> PURE_STANZA = [
            path_grouping_policy: 'group_by_prio',
            prio                : 'alua',
            failback            : 'immediate',
            path_checker        : 'tur',
            path_selector       : 'service-time 0',
            hardware_handler    : '1 alua',
            fast_io_fail_tmo    : '10',
            no_path_retry       : 'fail',
    ]

    List<Result> check(HostFacts f, Context ctx) {
        List<Result> r = []
        if (!f.complete) {
            r << new Result('PROBE', 'Host probe completed', Severity.HIGH, Status.FAIL, 'probe output truncated', 'all sections', null)
            return r
        }

        // ---- packages / services
        ['open-iscsi': Severity.HIGH, 'multipath-tools': Severity.HIGH, 'sg3-utils': Severity.LOW, 'gfs2-utils': Severity.LOW].each { String p, Severity sev ->
            boolean ok = f.packages[p]?.installed
            r << new Result('PKG-01', "${p} installed".toString(), sev, ok ? Status.PASS : (sev == Severity.HIGH ? Status.FAIL : Status.WARN),
                    ok ? f.packages[p].version as String : 'missing', 'installed', 'packages')
        }
        ['iscsid', 'multipathd'].each { String s ->
            Map st = f.services[s] ?: [:]
            boolean ok = st.active == 'active' && st.enabled in ['enabled', 'static', 'indirect']
            r << new Result(s == 'iscsid' ? 'ISC-05' : 'MP-01', "${s} running and enabled".toString(), Severity.HIGH,
                    ok ? Status.PASS : Status.FAIL, "${st.active ?: '?'}/${st.enabled ?: '?'}".toString(), 'active/enabled', 'services')
        }

        // ---- kernel
        if (f.versionId == '22.04') {
            int major = (f.kernel ?: '0').tokenize('.')[0] as Integer
            r << new Result('KRN-01', 'Ubuntu 22.04 runs the HWE kernel (GFS2 requirement)', Severity.HIGH,
                    major >= 6 ? Status.PASS : Status.FAIL, f.kernel, '>= 6.x (linux-generic-hwe-22.04)', null)
        }

        // ---- iSCSI identity, bindings, sessions
        r << new Result('ISC-00', 'iSCSI initiator name configured', Severity.HIGH, f.iqn ? Status.PASS : Status.FAIL,
                f.iqn ?: 'none in /etc/iscsi/initiatorname.iscsi', 'InitiatorName=iqn....', f.iqn ? null : 'iqn')
        boolean iqnOk = f.iqn && (!ctx.registeredIqns || ctx.registeredIqns*.toLowerCase().contains(f.iqn.toLowerCase()))
        r << new Result('ISC-01', 'Initiator IQN registered on the FlashArray', Severity.HIGH, iqnOk ? Status.PASS : Status.FAIL,
                f.iqn ?: 'none', 'IQN present on an array host', null)

        Set<String> storIfs = f.storageInterfaces
        // portals on a subnet this host has a NIC on; the rest are not storage paths for this host
        List<String> reachable = ctx.portals.findAll { f.routes[it] }
        List<String> viaGateway = ctx.portals.findAll { !f.routes[it] && f.gatewayed[it] }
        List<String> unrouted = ctx.portals.findAll { !f.routes[it] && !f.gatewayed[it] }
        r << new Result('NET-00', 'Array portals on a directly connected storage subnet', Severity.HIGH,
                unrouted || !reachable ? Status.FAIL : Status.PASS,
                unrouted ? "no route to ${unrouted.join(', ')}".toString() : (reachable ? "${reachable.size()} portal(s) via ${storIfs.sort().join(', ')}".toString() : 'none'),
                'a storage NIC on each portal subnet', null)
        if (viaGateway) {
            r << new Result('NET-00b', 'Portals reachable only through a gateway (not used)', Severity.MEDIUM, Status.WARN,
                    viaGateway.collect { "${it} (${f.gatewayed[it]})" }.join(', '),
                    'iSCSI is never routed: add a storage NIC on that subnet if the host should use these portals', null)
        }
        r << new Result('NET-03', 'Two storage interfaces (one per fabric)', Severity.MEDIUM,
                storIfs.size() >= 2 ? Status.PASS : Status.WARN, "${storIfs.size()} interface(s)".toString(), '>= 2', null)

        Set<String> boundNetdevs = f.ifaces.findAll { it.netdev }*.netdev as Set<String>
        List<String> unbound = storIfs.findAll { !boundNetdevs.contains(it) }.sort()
        r << new Result('ISC-02', 'iSCSI iface bound to each storage NIC', Severity.HIGH, unbound ? Status.FAIL : Status.PASS,
                unbound ? "unbound: ${unbound.join(', ')}".toString() : boundNetdevs.sort().join(', '), 'iface.net_ifacename per NIC', 'iscsi-sessions')

        List<Map> live = f.pureSessions.findAll { it.state == 'LOGGED_IN' }
        List<String> noSession = reachable.findAll { String p -> !live.any { it.portal == p } }
        r << new Result('ISC-03', 'Logged-in session to every directly connected portal', Severity.HIGH, noSession ? Status.FAIL : Status.PASS,
                "${live.size()} session(s)${noSession ? '; missing ' + noSession.join(', ') : ''}".toString(),
                "${reachable.size()} (one per portal on this host's storage subnets)".toString(), 'iscsi-sessions')
        List<Map> crossFabric = live.findAll { Map s -> f.routes[s.portal] && s.viaNic && s.viaNic != f.routes[s.portal] }
        if (crossFabric) {
            r << new Result('ISC-03b', 'Sessions use the interface on the portal\'s own fabric', Severity.MEDIUM, Status.WARN,
                    crossFabric.collect { "${it.portal} via ${it.viaNic}" }.join(', '), 'portal via its routed NIC', null)
        }
        List<Map> unboundSessions = live.findAll { it.iface == 'default' }
        if (unboundSessions) {
            r << new Result('ISC-02b', 'Sessions bound to a named iface (not "default")', Severity.LOW, Status.WARN,
                    unboundSessions*.portal.join(', '), 'per-NIC iface (Morpheus UI targets use "default")', null)
        }

        List<Map> pureNodes = f.nodes.findAll { (it['node.name'] as String)?.contains('purestorage') }
        List<Map> manual = pureNodes.findAll { it['node.startup'] != 'automatic' }
        r << new Result('ISC-04', 'node.startup = automatic for FlashArray targets', Severity.HIGH,
                pureNodes ? (manual ? Status.FAIL : Status.PASS) : Status.NA,
                manual ? "${manual.size()} record(s) not automatic".toString() : "${pureNodes.size()} record(s)".toString(), 'automatic', 'node-startup')
        List<Map> badNoop = pureNodes.findAll {
            it['node.conn[0].timeo.noop_out_interval'] != '5' || it['node.conn[0].timeo.noop_out_timeout'] != '5'
        }
        r << new Result('ISC-06', 'NOP-Out interval/timeout 5/5', Severity.MEDIUM, pureNodes ? (badNoop ? Status.WARN : Status.PASS) : Status.NA,
                badNoop ? "${badNoop.size()} record(s) differ".toString() : '5/5', '5/5', 'node-startup')

        // ---- multipath (effective config)
        if (!f.mpPure) {
            r << new Result('MP-02', 'Effective PURE multipath stanza', Severity.HIGH, Status.FAIL, 'no PURE device entry', 'PURE/FlashArray stanza', 'multipath')
        } else {
            PURE_STANZA.each { String k, String want ->
                String have = f.mpPure[k]
                boolean ok = k == 'no_path_retry' ? (have in ['fail', '0']) : have == want
                String id = k == 'no_path_retry' ? 'MP-03' : (k == 'fast_io_fail_tmo' ? 'MP-04' : 'MP-02')
                r << new Result(id, "multipath ${k}".toString(), id == 'MP-04' ? Severity.MEDIUM : Severity.HIGH,
                        ok ? Status.PASS : Status.FAIL, have ?: 'unset', want, 'multipath')
            }
            if (needsDetectPrioNo(f.packages['multipath-tools']?.version as String, ctx.purityVersion)) {
                boolean ok = f.mpPure.detect_prio == 'no'
                r << new Result('MP-07', 'detect_prio no (multipath-tools < 0.9.6 with this Purity)', Severity.MEDIUM,
                        ok ? Status.PASS : Status.FAIL, f.mpPure.detect_prio ?: 'unset', 'no', 'multipath')
            }
        }
        List<Map> queueing = f.pureMaps.findAll { it.queueing && !(it.queueing in ['off', '-']) }
        r << new Result('MP-03b', 'FlashArray maps not queueing I/O forever', Severity.HIGH,
                f.pureMaps ? (queueing ? Status.FAIL : Status.PASS) : Status.NA,
                queueing ? queueing.collect { "${it.wwid}:${it.queueing}" }.join(', ') : "${f.pureMaps.size()} map(s)".toString(), 'off', 'multipath')
        List<Map> aliased = f.pureMaps.findAll { it.name != it.wwid }
        r << new Result('MP-05', 'Maps named by WWID (user_friendly_names no)', Severity.HIGH,
                f.pureMaps ? (aliased ? Status.FAIL : Status.PASS) : Status.NA,
                aliased ? aliased.collect { "${it.name}=${it.wwid}" }.join(', ') : 'all by WWID', '/dev/mapper/3624a9370<serial>', 'multipath')

        f.pureMaps.each { Map m ->
            List<Map> ps = f.purePaths.findAll { it.wwid == m.wwid }
            List<Map> bad = ps.findAll { !(it.dmState == 'active' && it.checker == 'ready') }
            Set prios = ps*.prio as Set
            Status st = bad ? Status.FAIL : (ps.size() < live.size() ? Status.WARN : (prios.size() > 1 ? Status.WARN : Status.PASS))
            String note = "${ps.size() - bad.size()}/${ps.size()} healthy, sessions ${live.size()}${prios.size() > 1 ? ', mixed prio ' + prios.join('/') : ''}"
            // no automatic fix: a multipath drop-in does not add missing paths or settle mixed priorities
            r << new Result('MP-08', "Paths for ${m.wwid}".toString(), Severity.HIGH, st, note.toString(),
                    'all paths active/ready, one per session, single prio group', null)
        }

        // ---- block queue settings (udev); ids as in the research doc (UDV-04 is the SCSI timeout below)
        List<Map> sd = f.queues.findAll { (it.dev as String).startsWith('sd') }
        [
                ['UDV-01', 'scheduler none', Severity.MEDIUM, { Map q -> q.scheduler == 'none' }],
                ['UDV-02', 'add_random 0', Severity.LOW, { Map q -> q.addRandom == '0' }],
                ['UDV-03', 'rq_affinity 2', Severity.LOW, { Map q -> q.rqAffinity == '2' }],
                ['UDV-05', 'max_sectors_kb <= 4096', Severity.MEDIUM, { Map q -> (q.maxSectorsKb ?: '0').isInteger() && (q.maxSectorsKb as Integer) <= 4096 }],
        ].each { String id, String name, Severity sev, Closure<Boolean> ok ->
            List<Map> bad = f.queues.findAll { !ok(it) }
            r << new Result(id, "Queue ${name} on FlashArray devices".toString(), sev,
                    f.queues ? (bad ? Status.FAIL : Status.PASS) : Status.NA,
                    bad ? "${bad.size()} device(s): ${bad*.dev.take(5).join(', ')}".toString() : "${f.queues.size()} device(s)".toString(), name, 'udev')
        }
        List<Map> badTimeout = sd.findAll { it.timeout != '60' }
        r << new Result('UDV-04', 'SCSI device timeout 60 s', Severity.MEDIUM, sd ? (badTimeout ? Status.FAIL : Status.PASS) : Status.NA,
                badTimeout ? "${badTimeout.size()} device(s)".toString() : '60', '60', 'udev')

        // ---- network
        // MTU is never remediated automatically: the change bounces every link of the host's switch (management too),
        // the plugin reaches the host over that link, and a switch path without jumbo frames black-holes the traffic
        storIfs.each { String nic ->
            Integer m = f.mtu[nic]
            boolean ok = m == 9000
            r << new Result('NET-01', "MTU on ${nic}".toString(), Severity.HIGH, ok ? Status.PASS : Status.FAIL, "${m}".toString(), '9000', null,
                    ok ? null : mtuHint(nic, f.netplanFiles[nic]))
        }
        reachable.each { String p ->
            if (f.jumboPing.containsKey(p)) {
                boolean ok = f.jumboPing[p]
                String hint = ok ? null : (f.mtu[f.routes[p]] == 9000
                        ? "This host is at MTU 9000 on ${f.routes[p]}, so a switch port (or the array port) between them does not pass jumbo frames: fix it on the switch."
                        : "Fix NET-01 (MTU on ${f.routes[p]}) first; this ping cannot pass until the host sends jumbo frames.")
                r << new Result('NET-02', "Jumbo frames to ${p}".toString(), Severity.HIGH, ok ? Status.PASS : Status.FAIL,
                        ok ? '8972-byte DF ping ok' : 'DF ping failed', 'end-to-end MTU 9000', null, hint)
            }
        }
        boolean sameSubnet = sharedSubnet(storIfs, f.addrs)
        storIfs.each { String nic ->
            int ign = effective(f, nic, 'arp_ignore')
            int ann = effective(f, nic, 'arp_announce')
            int rpf = effective(f, nic, 'rp_filter')
            if (sameSubnet) {
                r << new Result('NET-04', "ARP isolation on ${nic} (storage NICs share a subnet)".toString(), Severity.HIGH,
                        ign == 2 && ann == 2 ? Status.PASS : Status.FAIL, "arp_ignore=${ign} arp_announce=${ann}".toString(), '2/2', 'arp-sysctl')
                r << new Result('NET-05', "rp_filter on ${nic}".toString(), Severity.MEDIUM, rpf == 1 ? Status.FAIL : Status.PASS,
                        "${rpf}".toString(), '0 or 2 (strict breaks same-subnet multi-NIC)', 'arp-sysctl')
            }
        }

        // ---- GFS2
        List<String> withdrawn = f.gfs2Withdraw.findAll { k, v -> v == '1' }*.key
        r << new Result('GFS-02', 'No withdrawn GFS2 filesystems', Severity.HIGH,
                f.gfs2Withdraw ? (withdrawn ? Status.FAIL : Status.PASS) : Status.NA,
                withdrawn ? withdrawn.join(', ') : "${f.gfs2Withdraw.size()} mounted".toString(), 'withdraw=0', null)
        // a recovery clone mounted by the plugin (lock_nolock, private recover:<tag> table, /mnt/pure-recover-*) is the
        // intended state of an open recovery session, not a hazard
        Set<String> recoveryDevs = f.gfs2Mounts.findAll { (it.target as String)?.startsWith(HostScripts.RECOVERY_PREFIX) || (it.options as String)?.contains('locktable=recover:') }
                *.source as Set<String>
        Map<String, List<String>> byTable = f.gfs2Tables.findAll { !recoveryDevs.contains(it.key) }
                .groupBy { it.value }.collectEntries { k, v -> [(k): v*.key] } as Map<String, List<String>>
        List<String> dupes = byTable.findAll { k, v -> k && v.size() > 1 }.collect { k, v -> "${k}: ${v.join(', ')}".toString() }
        r << new Result('GFS-03', 'GFS2 lock tables unique (no un-resignatured snapshot clone visible)', Severity.HIGH,
                f.gfs2Tables ? (dupes ? Status.FAIL : Status.PASS) : Status.NA,
                dupes ? dupes.join('; ') : "${f.gfs2Tables.size()} GFS2 LUN(s)".toString(), 'unique per filesystem', null)
        // a pool Morpheus defined for a GFS2 datastore (/mnt/<uuid>) whose filesystem is no longer mounted here:
        // left behind by a datastore delete, or a datastore not (yet) mounted on this host
        List<Map> stalePools = stalePools(f)
        r << new Result('LIB-01', 'No libvirt pools for GFS2 datastores that are not mounted', Severity.MEDIUM,
                f.pools ? (stalePools ? Status.WARN : Status.PASS) : Status.NA,
                stalePools ? stalePools.collect { "${it.name} -> ${it.target} (${it.state ?: '?'})" }.join(', ') : "${f.pools.size()} pool(s)".toString(),
                'every /mnt/<uuid> pool backed by a mounted GFS2 filesystem', stalePools ? 'stale-pools' : null)
        List<Map> aliasMounts = f.aliasMounts
        r << new Result('GFS-01', 'GFS2 mounted by WWID device path', Severity.HIGH,
                f.gfs2Mounts ? (aliasMounts ? Status.FAIL : Status.PASS) : Status.NA,
                aliasMounts ? aliasMounts*.source.join(', ') : "${f.gfs2Mounts.size()} mount(s)".toString(), '/dev/mapper/3<wwid>', null)
        r
    }

    /** How to raise the MTU of a storage interface, by whoever owns the host's network config. */
    static String mtuHint(String nic, String netplanFile) {
        String window = 'In a maintenance window (host in maintenance mode, no VMs): the change bounces every link on it, management included.'
        def m = netplanFile =~ /99-hvmcli-virtswitch-(.+)\.yaml$/
        if (m.find()) {
            return "${window} The network was built with hvmcli: sudo hvmcli virtswitch edit --virtswitch-name ${m.group(1)} --mtu 9000 --force " +
                    '(sets the uplinks, bond, bridges and VLANs). Then Rescan the host and Check again.'
        }
        netplanFile ? "${window} Set mtu: 9000 on ${nic} and every interface under it (bond, uplinks, bridge) in ${netplanFile}, then sudo netplan apply. Then Rescan and Check again."
                : "${window} Set MTU 9000 on ${nic} and every interface under it (bond, uplinks, bridge) wherever this host's network is defined."
    }

    static List<Map> stalePools(HostFacts f) {
        Set<String> mounted = f.gfs2Mounts*.target as Set<String>
        f.pools.findAll { (it.target as String) ==~ HostScripts.GFS2_MOUNT_PATH && !mounted.contains(it.target) }
    }

    static int effective(HostFacts f, String nic, String key) {
        // Kernel uses max(all, interface) for arp_ignore/arp_announce/rp_filter.
        Math.max(intOr0(f.sysctl['all']?.get(key)), intOr0(f.sysctl[nic]?.get(key)))
    }

    private static int intOr0(String v) {
        v?.isInteger() ? v as Integer : 0
    }

    static boolean sharedSubnet(Collection<String> nics, Map<String, List<String>> addrs) {
        List<String> nets = nics.collectMany { String n -> (addrs[n] ?: []).collect { network(it) } }.findAll()
        nets.size() != (nets as Set).size()
    }

    /** "192.0.2.200/25" -> "192.0.2.128/25". */
    static String network(String cidr) {
        if (!cidr?.contains('/')) {
            return null
        }
        List<String> p = cidr.split('/') as List<String>
        int bits = p[1] as Integer
        long ip = p[0].tokenize('.').inject(0L) { long acc, String o -> (acc << 8) | (o as Long) }
        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL
        long net = ip & mask
        "${(net >> 24) & 255}.${(net >> 16) & 255}.${(net >> 8) & 255}.${net & 255}/${bits}".toString()
    }

    /** Pure KB: hosts on multipath-tools < 0.9.6 need detect_prio no unless Purity is >= 6.5.5 (6.5.x) or >= 6.6.4. */
    static boolean needsDetectPrioNo(String mpToolsVersion, String purity) {
        String up = (mpToolsVersion ?: '0').replaceFirst(/^\d+:/, '').split(/[-+~]/)[0]
        if (cmp(up, '0.9.6') >= 0) {
            return false
        }
        if (!purity) {
            return true
        }
        if (cmp(purity, '6.6.4') >= 0) {
            return false
        }
        !(purity.startsWith('6.5.') && cmp(purity, '6.5.5') >= 0)
    }

    static int cmp(String a, String b) {
        List<Integer> x = a.tokenize('.').collect { it.isInteger() ? it as Integer : 0 }
        List<Integer> y = b.tokenize('.').collect { it.isInteger() ? it as Integer : 0 }
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = (x[i] ?: 0) <=> (y[i] ?: 0)
            if (c) {
                return c
            }
        }
        0
    }
}
