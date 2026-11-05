package org.purehvm.compliance

import org.purehvm.ops.HostScripts

/**
 * Builds a bash script that fixes the remediable findings on one host. Deliberately conservative:
 *  - Only ADDS configuration: drop-in files (never rewrites /etc/multipath.conf, which Morpheus owns and
 *    `hvmcli storage multipath apply` replaces from a staged copy), node.startup/noop updates, new iSCSI
 *    ifaces and sessions. It never logs out a session, never restarts corosync/dlm, never runs `multipath -F`.
 *  - Never takes a host's finite no_path_retry back to 0.
 *  - Refuses the multipath change while a GFS2 filesystem is mounted through an alias (mpathX) device,
 *    because switching to WWID names would pull the device out from under it.
 *  - Follows Morpheus (HVM) where it owns iSCSI: on a host with hvmcli, sessions are made with the command Morpheus
 *    itself runs for a cluster iSCSI target (`hvmcli storage iscsi configure --portal`), only for portals that are
 *    Morpheus targets when the caller knows them, and an empty initiator name gets the IQN Morpheus has on record.
 *  - Works on a host with no iSCSI state at all: iscsiadm's "no records found" (exit 21) is not a failure there.
 */
class Remediation {
    static final String MULTIPATH_DROPIN = '/etc/multipath/conf.d/pure.conf'
    static final String UDEV_RULES = '/etc/udev/rules.d/99-pure-storage.rules'
    static final String SYSCTL_DROPIN = '/etc/sysctl.d/99-pure-iscsi.conf'

    /** The "Multipath no_path_retry" setting as a drop-in value: 0, or 1-60 retries. Anything else, queue included, is Pure's 0. */
    static String noPathRetrySetting(Object setting) {
        String s = (setting as String)?.trim()
        s?.isInteger() && (s as Integer) in 1..60 ? String.valueOf(s as Integer) : '0'
    }

    /**
     * What the drop-in sets on this host: the setting, except that 0 never replaces a finite value the host already has
     * unless lowering is explicitly allowed ("Multipath: allow lowering to 0").
     */
    static String dropInNoPathRetry(String setting, HostFacts f, boolean allowZero = false) {
        String host = f.noPathRetry.value
        setting == '0' && !allowZero && ComplianceChecker.finiteRetry(host) ? host : setting
    }

    static String multipathDropIn(boolean detectPrioNo, String noPathRetry = '0') {
        """# Managed by the Pure FlashArray for HVM Morpheus plugin - changes are overwritten.
# Values: Everpure Linux Recommended Settings (DM-Multipath) and HPE VME FC/iSCSI guides.
# no_path_retry: the plugin's "Multipath no_path_retry" setting, or this host's finite value.
devices {
    device {
        vendor                "PURE"
        product               "FlashArray"
        path_selector         "service-time 0"
        path_grouping_policy  group_by_prio
        prio                  alua
        hardware_handler      "1 alua"
        failback              immediate
        path_checker          tur
        fast_io_fail_tmo      10
        no_path_retry         ${noPathRetry}
        user_friendly_names   no${detectPrioNo ? '\n        detect_prio           no' : ''}
    }
}
""".stripIndent()
    }

    static String udevRules() {
        '''# Managed by the Pure FlashArray for HVM Morpheus plugin - changes are overwritten.
# Values: Everpure Linux Recommended Settings (Queue Settings, Maximum IO Size).
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/scheduler}="none"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/scheduler}="none"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/add_random}="0"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/add_random}="0"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/rq_affinity}="2"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/rq_affinity}="2"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{device/timeout}="60"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", ATTR{queue/max_sectors_kb}="4096"
'''
    }

    static String sysctlDropIn(Collection<String> nics) {
        StringBuilder sb = new StringBuilder('# Managed by the Pure FlashArray for HVM Morpheus plugin.\n')
        sb << '# Storage NICs share a subnet: answer ARP only on the owning NIC, loose reverse-path filtering.\n'
        (['all', 'default'] + nics.sort()).each { String i ->
            sb << "net.ipv4.conf.${i}.arp_ignore = 2\nnet.ipv4.conf.${i}.arp_announce = 2\nnet.ipv4.conf.${i}.rp_filter = 2\n"
        }
        sb.toString()
    }

    private static void writeFile(StringBuilder sb, String path, String content) {
        sb << "mkdir -p \"\$(dirname ${path})\"\n"
        sb << "echo '${content.getBytes('UTF-8').encodeBase64()}' | base64 -d > ${path}.new && mv ${path}.new ${path}\n"
    }

    static final String HEADER = '#!/bin/bash\nset -euo pipefail\nexport LC_ALL=C DEBIAN_FRONTEND=noninteractive PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' +
            'step() { echo "== $*"; }\n' +
            // iscsiadm exits 21 ("no records found") on a host with no sessions or node records yet: nothing to do, not an error
            'iscsi_ok() { local rc=0; "$@" || rc=$?; [ "$rc" -eq 0 ] || [ "$rc" -eq 21 ] || return "$rc"; }\n'
    static final int DISCOVERY_TIMEOUT_SEC = 30
    /** hvmcli's own discovery gives up after 30 s; this bounds one portal's configure (discovery + login). */
    static final int HVMCLI_CONFIGURE_TIMEOUT_SEC = 120

    /**
     * One self-contained script per fix, in order: [title, script]. The plugin runs them one at a time so the job shows
     * progress, and stops at the first failure with that step's output.
     * @param fixes remediation keys to apply (subset of the results' {@code fix} values)
     * @param portals array iSCSI portal addresses
     * @param noPathRetry the plugin setting, from {@link #noPathRetrySetting}
     * @param morpheus what Morpheus knows: {@code iqn} (the host's initiator name on record) and {@code targets} (the
     *        cluster's Morpheus iSCSI target addresses; null when unknown)
     */
    List<Map<String, String>> scriptSteps(HostFacts f, Collection<String> fixes, List<String> portals, String purityVersion, String noPathRetry = '0',
                                          Map morpheus = [:]) {
        steps(f, fixes, portals, purityVersion, noPathRetry, morpheus ?: [:]).collect { [title: it.title, script: HEADER + it.body] }
    }

    /** An IQN that can go into a shell script as is (letters, digits, . : -), or null. */
    static String safeIqn(String iqn) {
        String q = iqn?.trim()
        q && q ==~ /iqn\.\d{4}-\d{2}\.[A-Za-z0-9.:\-]+/ ? q : null
    }

    /** The portals a host should have sessions to: the array's, limited to the cluster's Morpheus targets when known. */
    static List<String> wantedPortals(List<String> portals, Collection<String> morpheusTargets) {
        morpheusTargets == null ? portals : portals.findAll { morpheusTargets.contains(it) }
    }

    private List<Map<String, String>> steps(HostFacts f, Collection<String> fixes, List<String> portals, String purityVersion, String noPathRetry, Map morpheus) {
        Set<String> want = fixes as Set<String>
        List<Map<String, String>> out = []
        Closure add = { String title, StringBuilder sb -> out << [title: title, body: "step \"${title}\"\n".toString() + sb.toString()] }

        if ('packages' in want) {
            // a freshly installed host can have stale package lists: refresh once and retry
            add('install packages', new StringBuilder('''PKGS="open-iscsi multipath-tools sg3-utils gfs2-utils"
if ! apt-get install -y -q $PKGS; then
  echo "install failed; refreshing package lists and retrying"
  apt-get update -q && apt-get install -y -q $PKGS
fi
'''))
        }
        if ('iqn' in want) {
            // only when none is set: changing an existing name would cut the host off from every LUN masked to it.
            // Morpheus generates the name when it adds a host; use its record so the host, Morpheus and the array agree.
            String recorded = safeIqn(morpheus.iqn as String)
            add('iSCSI initiator name', new StringBuilder('''if grep -qE '^InitiatorName=iqn\\.' /etc/iscsi/initiatorname.iscsi 2>/dev/null; then
  echo "initiator name already set: $(sed -n 's/^InitiatorName=//p' /etc/iscsi/initiatorname.iscsi)"
else
''' + (recorded ? "  IQN='${recorded}'; SRC='Morpheus record'\n" :
       "  IQN=\"iqn.\$(date +%Y-%m).com.hpe:\$(hostname -s | tr 'A-Z' 'a-z'):\$(( (RANDOM % 90000) + 10000 ))\"; SRC='new; Morpheus had none'\n") + '''  mkdir -p /etc/iscsi
  echo "InitiatorName=$IQN" > /etc/iscsi/initiatorname.iscsi.new && mv /etc/iscsi/initiatorname.iscsi.new /etc/iscsi/initiatorname.iscsi
  systemctl restart iscsid
  echo "set initiator name $IQN ($SRC)"
fi
'''))
        }
        if ('services' in want) {
            add('enable services', new StringBuilder('systemctl enable --now iscsid multipathd\n'))
        }
        if ('multipath' in want) {
            List<Map> aliasMounts = f.aliasMounts
            StringBuilder sb = new StringBuilder()
            if (aliasMounts) {
                sb << "echo 'SKIP multipath: GFS2 mounted via alias device(s) ${aliasMounts*.source.join(' ')}; fix in a maintenance window'\n"
            } else {
                boolean dp = ComplianceChecker.needsDetectPrioNo(f.packages['multipath-tools']?.version as String, purityVersion)
                String retry = dropInNoPathRetry(noPathRetry, f, morpheus.allowZero == true)
                writeFile(sb, MULTIPATH_DROPIN, multipathDropIn(dp, retry))
                String note = retry != noPathRetry ? " (kept from this host; the setting asks for ${noPathRetry})" :
                        (morpheus.allowZero == true && retry == '0' && ComplianceChecker.finiteRetry(f.noPathRetry.value) ? " (lowered from ${f.noPathRetry.value}: lowering to 0 is allowed)" : '')
                if (f.noPathRetry.source == 'overrides') { note += " (no effect: the overrides section sets ${f.noPathRetry.value})" }
                sb << "echo 'no_path_retry ${retry}${note}'\n"
                sb << 'multipathd reconfigure\n'
            }
            add('multipath drop-in', sb)
        }
        if ('udev' in want) {
            StringBuilder sb = new StringBuilder()
            writeFile(sb, UDEV_RULES, udevRules())
            sb << 'udevadm control --reload-rules\nudevadm trigger --subsystem-match=block --action=change\n'
            add('udev rules', sb)
        }
        if ('arp-sysctl' in want && f.storageInterfaces) {
            StringBuilder sb = new StringBuilder()
            writeFile(sb, SYSCTL_DROPIN, sysctlDropIn(f.storageInterfaces))
            sb << "sysctl -q -p ${SYSCTL_DROPIN}\n"
            add('ARP sysctls', sb)
        }
        // before the sessions step: a dead iface makes hvmcli's discovery time out
        if ('stale-iscsi-records' in want) {
            StringBuilder sb = staleRecords(f)
            if (sb) {
                add('remove unused and dead iSCSI records (never a logged-in one)', sb)
            }
        }
        if ('iscsi-sessions' in want) {
            if (f.hvmcli) {
                add('iSCSI sessions via Morpheus (hvmcli, adds only)', hvmcliSessions(f, portals, morpheus.targets as Collection<String>))
            } else {
                add('iSCSI iface binding and sessions (adds only)', iscsiadmSessions(f, portals))
            }
        }
        if ('stale-pools' in want) {
            StringBuilder sb = new StringBuilder()
            ComplianceChecker.stalePools(f).each { Map p ->
                sb << HostScripts.removePoolBody(p.target as String, p.name as String)
            }
            if (sb) {
                add('remove libvirt pools of unmounted GFS2 datastores', sb)
            }
        }
        if ('node-startup' in want) {
            // a host with no node records yet makes `iscsiadm -m node` exit 21: nothing to update
            // Only the record each live session uses (per portal, per iface). A spare record of a portal that already has
            // a session stays as it is: set to automatic it adds a duplicate session at the next boot.
            add('node records: startup automatic, NOP-Out 5/5', new StringBuilder(LIVE_PAIRS + '''{ iscsi_ok iscsiadm -m node -P1 2>/dev/null; } | awk '/^Target:/ {t=$2} /^[[:space:]]+Portal:/ {split($2,a,","); p=a[1]} /Iface Name:/ {if (t ~ /purestorage/) print t, p, $3}' | while read -r target portal iface; do
  ip="${portal%:*}"
  if grep -q "^${ip}|" <<<"$LIVE" && ! grep -qxF "${ip}|${iface}" <<<"$LIVE"; then
    echo "left ${ip} via ${iface} alone: the session to ${ip} uses another record"
    continue
  fi
  for kv in node.startup=automatic node.conn[0].timeo.noop_out_interval=5 node.conn[0].timeo.noop_out_timeout=5; do
    iscsiadm -m node -T "$target" -p "$portal" -I "$iface" -o update -n "${kv%%=*}" -v "${kv#*=}"
  done
done
echo "FlashArray node records: $({ iscsi_ok iscsiadm -m node 2>/dev/null; } | grep -c purestorage || true)"
'''))
        }
        out
    }

    /** Sets LIVE to "portal-ip|iface" per logged-in session, read when the step runs (not from the probe). */
    static final String LIVE_PAIRS = 'LIVE="$({ iscsi_ok iscsiadm -m session -P1 2>/dev/null; } | awk \'/Current Portal:/ {split($3,a,":"); p=a[1]} /Iface Name:/ {print p "|" $3}\')"\n'

    private static boolean safeName(String s) {
        s && s ==~ /[A-Za-z0-9._:\-]+/
    }

    private static boolean safeIp(String s) {
        s && s ==~ /\d{1,3}(\.\d{1,3}){3}/
    }

    /**
     * ISC-07's fix. Deletes, one by one, the spare node records and the records and iface entries of dead or duplicate
     * ifaces the probe found, each only if no session uses it when the step runs. Never logs out, never deletes a
     * record by filter (an `iscsiadm -m node -o delete` that ignored its filter would remove them all).
     */
    private static StringBuilder staleRecords(HostFacts f) {
        StringBuilder sb = new StringBuilder(LIVE_PAIRS)
        List<Map> spare = ComplianceChecker.spareNodes(f)
        List<Map> dead = ComplianceChecker.deadIfaces(f)
        List<Map> ifaces = dead + ComplianceChecker.redundantIfaces(f, spare, dead)
        Set<String> ifaceNames = ifaces*.name as Set<String>
        List<Map> records = spare + f.nodes.findAll { ifaceNames.contains(it['iface.iscsi_ifacename']) && !spare.any { s -> s.is(it) } }
        int n = 0
        records.each { Map r ->
            String t = r['node.name'], p = r['node.conn[0].address'], i = r['iface.iscsi_ifacename']
            if (!(safeIqn(t) && safeIp(p) && safeName(i))) {
                sb << "echo 'SKIP a node record with unexpected characters; remove it by hand'\n"
                return
            }
            n++
            sb << "if grep -qxF '${p}|${i}' <<<\"\$LIVE\"; then echo 'kept ${p} via ${i}: logged in now'\n"
            sb << "else iscsiadm -m node -T '${t}' -p '${p}:3260' -I '${i}' -o delete && echo 'removed unused node record ${p} via ${i}' || echo 'WARN could not delete node record ${p} via ${i}'; fi\n"
        }
        ifaces.each { Map x ->
            String i = x.name, nd = x.netdev
            if (!(safeName(i) && safeName(nd))) {
                sb << "echo 'SKIP an iface record with unexpected characters; remove it by hand'\n"
                return
            }
            n++
            sb << "if grep -q '|${i}\$' <<<\"\$LIVE\"; then echo 'kept iface ${i}: a session uses it now'\n"
            sb << "else iscsiadm -m iface -I '${i}' -o delete >/dev/null && echo 'removed iface ${i} (bound to ${nd}, unused)' || echo 'WARN could not delete iface ${i}'; fi\n"
        }
        n ? sb : new StringBuilder()
    }

    /** An unroutable portal is skipped with the reason; true when the portal is a storage path for this host. */
    private static boolean routed(StringBuilder sb, HostFacts f, String p) {
        if (f.routes[p]) {
            return true
        }
        sb << (f.gatewayed[p] ? "echo 'SKIP portal ${p}: only reachable through a gateway (${f.gatewayed[p]}); not a storage path'\n"
                              : "echo 'SKIP portal ${p}: no route'\n")
        false
    }

    /**
     * Sessions the way Morpheus makes them for a cluster iSCSI target: `hvmcli storage iscsi configure --portal`
     * (discovery + login on the "default" iface; the kernel routes each session out of its fabric's NIC). Only portals
     * without a logged-in session; one that fails is reported and the others still run. Never passes --initiator-name:
     * the host keeps the name it has (the iqn step sets Morpheus's when there is none).
     */
    private static StringBuilder hvmcliSessions(HostFacts f, List<String> portals, Collection<String> morpheusTargets) {
        StringBuilder sb = new StringBuilder()
        Set<String> live = f.pureSessions.findAll { it.state == 'LOGGED_IN' }*.portal as Set<String>
        portals.each { String p ->
            if (!routed(sb, f, p)) {
                return
            }
            if (!wantedPortals([p], morpheusTargets)) {
                sb << "echo 'SKIP portal ${p}: not a Morpheus iSCSI target of this cluster (add it there, or from the plugin tab, and Morpheus logs every host in)'\n"
                return
            }
            if (live.contains(p)) {
                sb << "echo 'session to ${p} already up'\n"
                return
            }
            sb << "if OUT=\$(timeout ${HVMCLI_CONFIGURE_TIMEOUT_SEC} hvmcli storage iscsi configure --portal '${p}:3260' --json </dev/null 2>&1); then\n"
            sb << "  echo 'logged in to ${p} (hvmcli storage iscsi configure, as Morpheus does)'\n"
            sb << "else\n  echo \"WARN hvmcli configure ${p} failed: \$(echo \"\$OUT\" | tr '\\n' ' ' | tail -c 300)\"\n"
            sb << defaultIfaceFallback(p)
            sb << "fi\n"
        }
        sb << 'iscsi_ok iscsiadm -m session --rescan >/dev/null 2>&1 || echo "WARN session rescan failed"\n'
        sb << 'echo "FlashArray sessions now: $({ iscsi_ok iscsiadm -m session 2>/dev/null; } | grep -c purestorage || true)"\n'
        sb
    }

    /**
     * hvmcli's discovery names no iface, so iscsiadm tries every iface record on the host; one bound to the other
     * fabric's NIC (or a missing one) times it out even though the portal answers. Discovery and login limited to the
     * "default" iface give the same session Morpheus makes. `-o new` adds records and never rewrites existing ones.
     */
    private static String defaultIfaceFallback(String p) {
        """  if timeout ${DISCOVERY_TIMEOUT_SEC} iscsiadm -m discovery -t sendtargets -p ${p}:3260 -I default -o new >/dev/null 2>&1; then
    iscsiadm -m node -p ${p}:3260 -I default -o update -n node.startup -v automatic >/dev/null 2>&1 || true
    if iscsiadm -m node -p ${p}:3260 -I default --login >/dev/null 2>&1; then
      echo '  logged in to ${p} on the default iface (discovery limited to it); hvmcli configure fails on this host: look for an iSCSI iface record bound to a NIC that is down or gone'
    else
      echo "  WARN login to ${p} on the default iface failed (exit \$?)"
    fi
  else
    echo '  WARN discovery of ${p} on the default iface failed too: the host gets no answer from ${p}:3260 (VLAN, switch port, MTU)'
  fi
"""
    }

    /** Hosts without hvmcli (HVM 1.3): one iface per storage NIC, discovery and login through it. */
    private static StringBuilder iscsiadmSessions(HostFacts f, List<String> portals) {
        StringBuilder sb = new StringBuilder()
        Set<String> bound = f.ifaces.findAll { it.netdev }*.netdev as Set<String>
        Set<String> named = f.ifaces*.name as Set<String>
        f.storageInterfaces.sort().each { String nic ->
            if (!bound.contains(nic)) {
                // an iface record of that name may exist unbound: creating it again fails, so only create a missing one
                if (!named.contains(nic)) {
                    sb << "iscsiadm -m iface -I ${nic} >/dev/null 2>&1 || iscsiadm -m iface -I ${nic} --op=new >/dev/null\n"
                }
                sb << "iscsiadm -m iface -I ${nic} --op=update -n iface.net_ifacename -v ${nic} >/dev/null\n"
            }
        }
        Set<String> livePairs = f.pureSessions.findAll { it.state == 'LOGGED_IN' }.collect { "${it.portal}|${it.viaNic}".toString() } as Set<String>
        portals.each { String p ->
            if (!routed(sb, f, p)) {
                return
            }
            String nic = f.routes[p]
            String ifaceName = f.ifaces.find { it.netdev == nic }?.name ?: nic
            // a portal that does not answer must not abort the rest (iscsiadm exit 11 = PDU timeout)
            sb << "if timeout ${DISCOVERY_TIMEOUT_SEC} iscsiadm -m discovery -t sendtargets -p ${p}:3260 -I ${ifaceName} >/dev/null 2>&1; then\n"
            // Discovery through one iface returns every array portal; keep only same-fabric records not in use.
            portals.findAll { String other -> f.routes[other] && f.routes[other] != nic && !livePairs.contains("${other}|${nic}".toString()) }.each { String other ->
                sb << "  iscsiadm -m node -p ${other}:3260 -I ${ifaceName} -o delete >/dev/null 2>&1 || true\n"
            }
            if (!livePairs.contains("${p}|${nic}".toString())) {
                sb << "  iscsiadm -m node -p ${p}:3260 -I ${ifaceName} -o update -n node.startup -v automatic || echo 'WARN no node record for ${p} via ${ifaceName}'\n"
                sb << "  iscsiadm -m node -p ${p}:3260 -I ${ifaceName} --login >/dev/null 2>&1 && echo 'logged in to ${p} via ${ifaceName}' || echo 'WARN login to ${p} via ${ifaceName} failed'\n"
            } else {
                sb << "  echo 'session to ${p} via ${nic} already up'\n"
            }
            sb << "else\n  echo \"WARN discovery of ${p} via ${ifaceName} failed (exit \$?); portal skipped\"\nfi\n"
        }
        // no session at all yet (every login failed) is exit 21, not a failure of this step
        sb << 'iscsi_ok iscsiadm -m session --rescan >/dev/null 2>&1\n'
        sb
    }
}
