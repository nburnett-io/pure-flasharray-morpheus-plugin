package org.purehvm.compliance

import org.purehvm.api.PureNames

/**
 * Parsed output of pure-host-probe.sh. Every field reflects the host's EFFECTIVE state (multipath -t,
 * multipathd, iscsiadm records, sysfs), not its config files: Pure's own testing shows the files mislead.
 */
class HostFacts {
    String versionId
    String kernel
    Map<String, Map> packages = [:]          // name -> [version, installed]
    Map<String, Map> services = [:]          // name -> [active, enabled]
    String iqn
    List<Map> ifaces = []                    // [name, transport, mac, ip, netdev]
    List<Map> sessions = []                  // [target, portal, iface, netdev, state]
    List<Map> nodes = []                     // node records: key -> value
    Map<String, String> mpPure = [:]         // effective PURE device stanza
    List<Map> maps = []                      // [wwid, name, queueing, paths]
    List<Map> paths = []                     // [wwid, dev, dmState, checker, prio, vendor]
    List<Map> queues = []                    // [dev, scheduler, addRandom, rqAffinity, maxSectorsKb, timeout]
    Map<String, String> routes = [:]         // portal -> outgoing interface, directly connected portals only
    Map<String, String> gatewayed = [:]      // portal -> "dev via gw": reachable only through a router, not a storage path
    Map<String, Integer> mtu = [:]           // interface -> MTU
    Map<String, List<String>> addrs = [:]    // interface -> [cidr]
    Map<String, Boolean> jumboPing = [:]     // portal -> 8972-byte DF ping ok
    Map<String, Map<String, String>> sysctl = [:]
    Map<String, String> netplanFiles = [:]   // storage interface -> /etc/netplan file defining it ('' when none)
    List<Map> gfs2Mounts = []                // [source, target, options]
    Map<String, String> gfs2Withdraw = [:]   // lock table -> 0/1
    Map<String, String> gfs2Tables = [:]     // device -> lock table
    List<Map> pools = []                     // libvirt storage pools [name, target, state]
    boolean hvmcli
    boolean complete                         // saw the @@end marker

    static HostFacts parse(String output) {
        Map<String, List<String>> sections = [:]
        String current = null
        (output ?: '').readLines().each { String line ->
            if (line.startsWith('@@')) {
                current = line.substring(2).trim()
                sections[current] = []
            } else if (current != null) {
                sections[current] << line
            }
        }
        HostFacts f = new HostFacts()
        f.complete = sections.containsKey('end')

        kv(sections.os).with { Map m -> f.versionId = m.VERSION_ID; f.kernel = m.KERNEL }
        split(sections.pkgs).each { List<String> p -> f.packages[p[0]] = [version: p[1], installed: (p[2] ?: '').startsWith('ii')] }
        split(sections.services).each { List<String> p -> f.services[p[0]] = [active: p[1], enabled: p[2]] }
        f.iqn = sections.iqn?.find { it?.trim() }?.trim()

        sections.ifaces?.findAll { it.trim() }?.each { String l ->
            List<String> parts = l.trim().split(/\s+/, 2) as List<String>
            List<String> attrs = parts.size() > 1 ? (parts[1].split(',', -1) as List<String>) : []
            f.ifaces << [name: parts[0], transport: attrs[0], mac: clean(attrs[1]), ip: clean(attrs[2]), netdev: clean(attrs[3])]
        }

        String target = null
        Map sess = null
        sections.sessions?.each { String raw ->
            String l = raw.trim()
            if (l.startsWith('Target:')) {
                target = l.substring(7).trim().split(/\s+/)[0]
            } else if (l.startsWith('Current Portal:')) {
                sess = [target: target, portal: l.substring(15).trim().split(',')[0].replaceFirst(/:\d+$/, '')]
                f.sessions << sess
            } else if (sess != null && l.startsWith('Iface Name:')) {
                sess.iface = l.substring(11).trim()
            } else if (sess != null && l.startsWith('Iface Netdev:')) {
                // unbound sessions print "<empty>" on older open-iscsi and the literal "default" on Ubuntu 24.04+
                String nd = clean(l.substring(13).trim())
                sess.netdev = nd == 'default' ? null : nd
            } else if (sess != null && l.startsWith('iSCSI Session State:')) {
                sess.state = l.substring(20).trim()
            }
        }

        Map rec = null
        sections.nodes?.each { String l ->
            if (l.startsWith('# BEGIN RECORD')) {
                rec = [:]
            } else if (l.startsWith('# END RECORD')) {
                if (rec) {
                    f.nodes << rec
                }
                rec = null
            } else if (rec != null && l.contains(' = ')) {
                int i = l.indexOf(' = ')
                rec[l.substring(0, i).trim()] = clean(l.substring(i + 3).trim())
            }
        }

        boolean inDevice = false
        sections.mpconf?.each { String raw ->
            String l = raw.trim()
            if (l.startsWith('device {') || l == '}') {
                inDevice = l != '}'
                return
            }
            List<String> kvp = l.split(/\s+/, 2) as List<String>
            if (inDevice && kvp.size() == 2) {
                f.mpPure[kvp[0]] = kvp[1].replaceAll('^"|"$', '')
            }
        }

        split(sections.maps).each { List<String> p -> f.maps << [wwid: p[0], name: p[1], queueing: p[2], paths: (p[3] ?: '0').trim()] }
        split(sections.paths).each { List<String> p -> f.paths << [wwid: p[0], dev: p[1], dmState: p[2], checker: p[3], prio: p[4], vendor: p[5]] }
        split(sections.sysfs).each { List<String> p ->
            f.queues << [dev: p[0], scheduler: p[1], addRandom: p[2], rqAffinity: p[3], maxSectorsKb: p[4], timeout: p[5]]
        }
        // 0.4.4+ probes add the gateway; a portal behind one (e.g. a third array subnet reached over mgmt's default
        // route) is not a storage path, and iscsiadm discovery bound to that NIC just times out
        split(sections.routes).each { List<String> p ->
            if (p[1] && p.size() > 2 && p[2]) {
                f.gatewayed[p[0]] = "${p[1]} via ${p[2]}".toString()
            } else if (p[1]) {
                f.routes[p[0]] = p[1]
            }
        }
        split(sections.links).each { List<String> p -> if (p[1]?.isInteger()) { f.mtu[p[0]] = p[1] as Integer } }
        split(sections.addrs).each { List<String> p -> f.addrs.computeIfAbsent(p[0]) { [] } << p[1] }
        split(sections.ping).each { List<String> p -> f.jumboPing[p[0]] = p[1] == 'ok' }
        split(sections.sysctl).each { List<String> p -> f.sysctl.computeIfAbsent(p[0]) { [:] }[p[1]] = p[2] }
        split(sections.netplan).each { List<String> p -> f.netplanFiles[p[0]] = p.size() > 1 ? p[1] : '' }
        sections.gfs2mounts?.findAll { it.trim() }?.each { String l ->
            List<String> p = l.trim().split(/\s+/) as List<String>
            f.gfs2Mounts << [source: p[0], target: p[1], options: p[2]]
        }
        split(sections.gfs2withdraw).each { List<String> p -> f.gfs2Withdraw[p[0]] = p[1] }
        split(sections.gfs2tables).each { List<String> p -> f.gfs2Tables[p[0]] = p[1] }
        split(sections.pools).each { List<String> p -> if (p[0]) { f.pools << [name: p[0], target: p.size() > 1 ? p[1] : null, state: p.size() > 2 ? p[2] : null] } }
        f.hvmcli = sections.hvmcli?.any { it.trim() } ?: false
        // Sessions on the "default" iface carry no netdev: the kernel routes them, so the route is the NIC.
        f.sessions.each { Map s -> s.viaNic = s.netdev ?: f.routes[s.portal as String] }
        f
    }

    private static String clean(String v) {
        v == null || v == '<empty>' || v.isBlank() ? null : v
    }

    private static Map kv(List<String> lines) {
        (lines ?: []).findAll { it.contains('=') }.collectEntries { String l ->
            int i = l.indexOf('=')
            [(l.substring(0, i)): l.substring(i + 1)]
        }
    }

    private static List<List<String>> split(List<String> lines) {
        (lines ?: []).findAll { it.trim() }.collect { it.trim().split(/\|/, -1) as List<String> }
    }

    /** Interfaces the host uses to reach the array's iSCSI portals (one per fabric in a dual-fabric design). */
    Set<String> getStorageInterfaces() {
        routes.values() as Set<String>
    }

    List<Map> getPureSessions() {
        sessions.findAll { (it.target as String)?.contains('purestorage') }
    }

    List<Map> getPureMaps() {
        maps.findAll { (it.wwid as String)?.startsWith(PureNames.WWID_PREFIX) }
    }

    List<Map> getPurePaths() {
        paths.findAll { (it.wwid as String)?.startsWith(PureNames.WWID_PREFIX) }
    }

    /** GFS2 mounted through an alias (mpathX) instead of /dev/mapper/3<wwid>: compliance fails it, remediation refuses to rename. */
    List<Map> getAliasMounts() {
        gfs2Mounts.findAll { !(it.source as String)?.startsWith('/dev/mapper/3') }
    }
}
