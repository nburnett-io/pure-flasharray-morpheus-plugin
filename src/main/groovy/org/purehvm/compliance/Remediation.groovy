package org.purehvm.compliance

import org.purehvm.ops.HostScripts

/**
 * Builds a bash script that fixes the remediable findings on one host. Deliberately conservative:
 *  - Only ADDS configuration: drop-in files (never rewrites /etc/multipath.conf, which Morpheus owns),
 *    node.startup/noop updates, new iSCSI ifaces and sessions. It never logs out a session, never
 *    restarts corosync/dlm, never runs `multipath -F`.
 *  - Refuses the multipath change while a GFS2 filesystem is mounted through an alias (mpathX) device,
 *    because switching to WWID names would pull the device out from under it.
 */
class Remediation {
    static final String MULTIPATH_DROPIN = '/etc/multipath/conf.d/pure.conf'
    static final String UDEV_RULES = '/etc/udev/rules.d/99-pure-storage.rules'
    static final String SYSCTL_DROPIN = '/etc/sysctl.d/99-pure-iscsi.conf'

    static String multipathDropIn(boolean detectPrioNo) {
        """# Managed by the Pure FlashArray for HVM Morpheus plugin - changes are overwritten.
# Values: Everpure Linux Recommended Settings (DM-Multipath) and HPE VME FC/iSCSI guides.
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
        no_path_retry         0
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

    static final String HEADER = '#!/bin/bash\nset -euo pipefail\nexport LC_ALL=C DEBIAN_FRONTEND=noninteractive PATH=/usr/sbin:/usr/bin:/sbin:/bin\n' +
            'step() { echo "== $*"; }\n'
    static final int DISCOVERY_TIMEOUT_SEC = 30

    /**
     * One self-contained script per fix, in order: [title, script]. The plugin runs them one at a time so the job shows
     * progress, and stops at the first failure with that step's output.
     * @param fixes remediation keys to apply (subset of the results' {@code fix} values)
     * @param portals array iSCSI portal addresses
     */
    List<Map<String, String>> scriptSteps(HostFacts f, Collection<String> fixes, List<String> portals, String purityVersion) {
        steps(f, fixes, portals, purityVersion).collect { [title: it.title, script: HEADER + it.body] }
    }

    private List<Map<String, String>> steps(HostFacts f, Collection<String> fixes, List<String> portals, String purityVersion) {
        Set<String> want = fixes as Set<String>
        List<Map<String, String>> out = []
        Closure add = { String title, StringBuilder sb -> out << [title: title, body: "step \"${title}\"\n".toString() + sb.toString()] }

        if ('packages' in want) {
            add('install packages', new StringBuilder('apt-get install -y -q open-iscsi multipath-tools sg3-utils gfs2-utils\n'))
        }
        if ('iqn' in want) {
            // only when none is set: changing an existing name would cut the host off from every LUN masked to it
            add('iSCSI initiator name', new StringBuilder('''if grep -qE '^InitiatorName=iqn\\.' /etc/iscsi/initiatorname.iscsi 2>/dev/null; then
  echo "initiator name already set: $(sed -n 's/^InitiatorName=//p' /etc/iscsi/initiatorname.iscsi)"
else
  IQN="iqn.$(date +%Y-%m).com.hpe:$(hostname -s | tr 'A-Z' 'a-z'):$(( (RANDOM % 90000) + 10000 ))"
  mkdir -p /etc/iscsi
  echo "InitiatorName=$IQN" > /etc/iscsi/initiatorname.iscsi.new && mv /etc/iscsi/initiatorname.iscsi.new /etc/iscsi/initiatorname.iscsi
  systemctl restart iscsid
  echo "set initiator name $IQN"
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
                writeFile(sb, MULTIPATH_DROPIN, multipathDropIn(dp))
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
        if ('iscsi-sessions' in want) {
            StringBuilder sb = new StringBuilder()
            Set<String> bound = f.ifaces.findAll { it.netdev }*.netdev as Set<String>
            f.storageInterfaces.sort().each { String nic ->
                if (!bound.contains(nic)) {
                    sb << "iscsiadm -m iface -I ${nic} --op=new >/dev/null\n"
                    sb << "iscsiadm -m iface -I ${nic} --op=update -n iface.net_ifacename -v ${nic} >/dev/null\n"
                }
            }
            Set<String> livePairs = f.pureSessions.findAll { it.state == 'LOGGED_IN' }.collect { "${it.portal}|${it.viaNic}".toString() } as Set<String>
            portals.each { String p ->
                String nic = f.routes[p]
                if (!nic) {
                    sb << (f.gatewayed[p] ? "echo 'SKIP portal ${p}: only reachable through a gateway (${f.gatewayed[p]}); not a storage path'\n"
                                          : "echo 'SKIP portal ${p}: no route'\n")
                    return
                }
                String ifaceName = f.ifaces.find { it.netdev == nic }?.name ?: nic
                // a portal that does not answer must not abort the rest (iscsiadm exit 11 = PDU timeout)
                sb << "if timeout ${DISCOVERY_TIMEOUT_SEC} iscsiadm -m discovery -t sendtargets -p ${p}:3260 -I ${ifaceName} >/dev/null 2>&1; then\n"
                // Discovery through one iface returns every array portal; keep only same-fabric records not in use.
                portals.findAll { String other -> f.routes[other] && f.routes[other] != nic && !livePairs.contains("${other}|${nic}".toString()) }.each { String other ->
                    sb << "  iscsiadm -m node -p ${other}:3260 -I ${ifaceName} -o delete >/dev/null 2>&1 || true\n"
                }
                if (!livePairs.contains("${p}|${nic}".toString())) {
                    sb << "  iscsiadm -m node -p ${p}:3260 -I ${ifaceName} -o update -n node.startup -v automatic\n"
                    sb << "  iscsiadm -m node -p ${p}:3260 -I ${ifaceName} --login >/dev/null 2>&1 && echo 'logged in to ${p} via ${ifaceName}' || echo 'WARN login to ${p} via ${ifaceName} failed'\n"
                } else {
                    sb << "  echo 'session to ${p} via ${nic} already up'\n"
                }
                sb << "else\n  echo \"WARN discovery of ${p} via ${ifaceName} failed (exit \$?); portal skipped\"\nfi\n"
            }
            sb << 'iscsiadm -m session --rescan >/dev/null\n'
            add('iSCSI iface binding and sessions (adds only)', sb)
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
            add('node records: startup automatic, NOP-Out 5/5', new StringBuilder('''iscsiadm -m node 2>/dev/null | awk '/purestorage/ {split($1,a,","); print a[1], $2}' | while read -r portal target; do
  for kv in node.startup=automatic node.conn[0].timeo.noop_out_interval=5 node.conn[0].timeo.noop_out_timeout=5; do
    iscsiadm -m node -T "$target" -p "$portal" -o update -n "${kv%%=*}" -v "${kv#*=}"
  done
done
'''))
        }
        out
    }
}
