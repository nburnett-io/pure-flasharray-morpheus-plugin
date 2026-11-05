# Changelog

## 0.13.5 (2026-11-05)

**Unused iSCSI records, host-group names, and most of the backlog.**

- **Fix: Remediate no longer adds sessions at the next boot.** The node-records fix (ISC-04/ISC-06) updated every
  FlashArray node record of a portal, including spare ones that no session uses (left by an older plugin's iface
  records, or by a rebuilt iSCSI virtual switch). Set to automatic, they log in at boot next to the records in use and
  the host comes up with extra paths. It now updates only the record each live session uses, by iface, and ISC-04/06
  judge only those.
- **New check ISC-07: unused or dead iSCSI records**, with a fix. Spare node records (not logged in while their portal
  has a session through another iface), iface records bound to a NIC that is missing, down or has no address (a rebuilt
  virtual switch leaves them, and they make `hvmcli storage iscsi configure` time out), and duplicate ifaces left with
  nothing. The fix deletes them record by record, each only if no session uses it when the step runs; it never logs out
  and never deletes by filter. FAIL when a dead iface would break Morpheus's own configure, otherwise WARN. It runs
  before the sessions step, also on Add host and on a joining host. The probe now reads link states.
- **Host group name taken by an old group: rename it to `<name>-old`.** When the cluster's name is already used by
  another host group (an old cluster's), the Hosts section offers to rename that group to `<name>-old` (or `-old-2`...)
  and create the cluster's group under the name, instead of `<name>-1`. A rename keeps the old group's hosts and volume
  connections. Refused when any cluster is linked to the old group.
- **A joining host waits for its iSCSI networking.** If ADD_WORKER arrives before the host reaches any array portal
  (Morpheus still building the iSCSI virtual switch), the add waits, checking every 2 minutes for up to 10 minutes,
  then runs. The wait is on a timer, not in a job, so the cluster's other operations are not held up. A host leaving the
  cluster is now written to the audit log (its array host is still never removed automatically).
- **Leftover recovery clones.** New section listing recovery clones on the cluster's arrays that no linked cluster owns
  (its cluster was deleted or unlinked, its host is gone), which no timeout ever closes, with a Remove button: cleaned up
  through its host when that host is in this cluster, otherwise only when nothing is connected; destroyed, not
  eradicated. A cluster's clone with no expiry recorded now gets one.
- **Operations history survives a restart.** Finished jobs (the end of their log) are kept in the cluster's config, the
  last 15, and shown with the running ones.
- **Application-consistent snapshots, off by default** (setting *Allow application-consistent snapshots*). When on, the
  snapshot view offers to freeze the file systems of every running VM on the datastore (qemu guest agent, all hosts in
  parallel), take the snapshot and thaw. A VM without an agent is skipped and reported. Before each freeze the host arms
  a timer that thaws that guest after 60 s whatever happens to the plugin. The host GFS2 file system is never frozen.
- **Workflow task "Pure FlashArray snapshot".** An array snapshot of the GFS2 datastore(s) a VM is on, for workflows
  (for example before a change); optionally application-consistent when the setting above allows it.
- **`no_path_retry` follow-ups.** New setting *Multipath: allow lowering to 0* (off): when on, a setting of 0 may
  replace a host's finite value. The job log says when an out-of-range setting counts as 0. New check MP-03c reports a
  `multipaths {}` entry that sets no_path_retry for a FlashArray LUN (it beats the drop-in). The probe no longer runs
  `blkid`/`tunegfs2` on a map with no usable path (it could block), and bounds both with a timeout.
- Fix: MP-03 offered Remediate for a finite setting that differs from the host's value but showed PASS, and only failing
  or warning checks carry their fix, so the button never appeared. It is a WARN now.
- Fix: the probe never read a node record on a real host. Its filter dropped the `# END RECORD` lines, so no record was
  ever completed and ISC-04/ISC-06 showed "not applicable" on hosts that have records (the test data kept those lines).
  The probe keeps them now, and the parser also ends a record where the next one begins.
- Review fixes before release:
  - A guest freeze that fails or times out is thawed straight away and keeps its 60 s safety timer, because the agent may
    still finish the freeze after the client gives up. A thaw that fails keeps the timer too.
  - Every host is frozen at once rather than in batches of 8. A snapshot is reported as not application-consistent when
    the freeze-to-snapshot time passed the safety timer. A bad suffix is refused before anything is frozen.
  - The workflow task runs only for an operator tenant of the cluster (master, the owning tenant, or *Operator tenants*),
    as the cluster tab does. A task whose tenant is unknown is refused.
  - ISC-07 removes an iface only when its NIC is missing or has no address. A NIC that is just down, such as a fabric in
    maintenance or a bridge still coming up, is reported and its iface kept.
  - A host joining while the cluster is busy is retried every 2 minutes, then given up with an audit entry, not dropped.
  - Renaming a host group that looks like an HVM cluster's (HPE initiator names, or plugin-tagged volumes connected)
    needs explicit confirmation, because a cluster on another appliance would not show up in this appliance's links.
  - One cluster's failure no longer stops the background sweeper for the others.
  - A record delete that fails in the cleanup is reported instead of failing the step.
  - Link changes and job history no longer overwrite each other's cluster config.
  - `hvmcli` and `virsh` read no stdin in the host scripts.
- Fix: two sessions to one portal were read as one. `iscsiadm -m session -P1` lists every session to a portal under one
  "Current Portal" heading, and the parser kept only the last interface, so a host logged in to each portal twice (on
  `default` and on a virtual-switch iface) showed half its sessions next to twice the paths. New check ISC-03c warns
  about two sessions to one portal over the same NIC (more paths, no more redundancy); no automatic fix.

## 0.13.1 (2026-11-05)

- **Fix: sessions on a host where `hvmcli storage iscsi configure` times out although the array answers.** hvmcli's
  discovery names no iSCSI iface, so `iscsiadm` tries every iface record on the host, and one bound to the other
  fabric's NIC, to a bridge that is down, or to a missing NIC times the whole discovery out. Seen on a new host whose
  iSCSI virtual switch had been rebuilt: the first switch's iface records stayed behind, bound to its bridges (down, no
  address), and configure timed out on all four portals while `iscsiadm -m discovery -t st -p <portal> -I default`
  listed the targets. When configure fails for a
  portal, Remediate (and Add / host join) now runs discovery and login for that portal on the `default` iface only,
  the session Morpheus would have made, adding node records without rewriting existing ones. The job log says when the
  fallback was needed (look at `iscsiadm -m iface` on that host) or when the portal does not answer at all.

## 0.13.0 (2026-11-05)

**Hosts with no iSCSI set up yet, and iSCSI that follows Morpheus.** On HVM 2.0 Morpheus builds a host's iSCSI
networking and initiator name when it adds the host, and logs every host in to each cluster iSCSI target with
`hvmcli storage iscsi configure --portal <ip>:3260`. Until now the plugin could only finish iSCSI on a host that already
had some: on a host with no sessions or node records, Remediate stopped at its first `iscsiadm` call.

- **Fix: Remediate works on a host with no iSCSI state.** `iscsiadm` exits 21 ("no records found") when there is
  nothing to list; the node-records and session-rescan steps treated that as a failure under `set -e`. Reproduced on a
  test host with its iSCSI records hidden: the 0.12.1 step exits 21, the 0.13.0 steps finish.
- **Fix: a check that does not apply offers no fix.** Remediate collected the fix of every result that was not PASS,
  so a new host (no node records: ISC-04/06 not applicable) was sent `node-startup`, the step that failed. Only FAIL and
  WARN results carry a fix now, in the checker and in the tab.
- **Fix: the host scripts find `hvmcli`.** HVM installs it in `/usr/local/bin`, which the probe's and the scripts' fixed
  `PATH` left out, so the probe reported no `hvmcli` on every host (and the MTU hint fell back to the netplan file).
- **Sessions the way Morpheus makes them.** On a host with `hvmcli`, Remediate's sessions step runs Morpheus's own
  command, `hvmcli storage iscsi configure --portal`, for each portal without a logged-in session, and only for the
  cluster's Morpheus iSCSI targets when they are known (the tab reads them as the user). It no longer creates iSCSI
  ifaces or logs in by itself there; the per-NIC iface check (ISC-02) points to the host's iSCSI virtual switch instead.
  Hosts without `hvmcli` (HVM 1.3) keep the `iscsiadm` path, now idempotent about existing iface records.
- **The initiator name is Morpheus's.** A host with no initiator name gets the IQN Morpheus has on record for it; a new
  HPE-style name is made only when Morpheus has none, and is written back to the Morpheus record. An existing name is
  still never replaced.
- **Adding a host sets up its iSCSI first.** Add (in the Hosts table) and the automatic add when a host joins the
  cluster now apply every failing automatic fix (packages, initiator name, services, multipath and udev drop-ins, ARP
  sysctls, sessions, node records) before the array step, then add the host to the host group with its real IQN and
  rescan. Sessions follow the Morpheus targets the other hosts are logged in to. A host with no IQN shows an Add button.
- **New tab section: iSCSI targets (Morpheus).** Each array portal, how every host reaches it, and whether it is a
  Morpheus iSCSI target of the cluster. Portals every host reaches directly but Morpheus does not manage can be added to
  Morpheus from there, as the user; Morpheus then logs every host in. New read-only route `portal-routes`.
- `apt-get install` refreshes the package lists and retries once when it fails.

Verified on test systems: the empty-host steps in a mount namespace with every iSCSI record hidden (HVM 1.3 and 2.0 test
hosts); Morpheus's initiator name written back on a 2.0 test host after its name was removed; `hvmcli configure` through
the new step against a live target (idempotent); on a test cluster, the new route, compliance with `hvmcli` detected,
Remediate following the four Morpheus targets (all already up) and Add host end to end (already set up, already in the
host group, rescan, datastore mounted). Not yet seen: a real host joining with no sessions to the FlashArray.

## 0.12.1 (2026-11-01)

- Wording only: MP-03's hint, the *Multipath no_path_retry* help text and the docs now describe the path-drop test
  (withdraw and host reset observed on a test host) instead of a reasoned chain. No check or remediation logic changed.

## 0.12.0 (2026-10-31)

**`no_path_retry` on GFS2 clusters.** Failing I/O about 20 s into an all-paths outage withdraws GFS2 on that host, and
the host is reset about two minutes later. Seen on a nested HVM 2.0 test host (Ubuntu 26.04, one iSCSI path dropped
for 40 s): at `fail`, `session recovery timed out` at 15 s, then `Error -5 writing to journal` and `file system
withdrawn` at once, and a reboot by the Morpheus agent (`reboot -f`) two minutes after that; at `12` the same drop was
held and I/O resumed with no error.
The other hosts did not stall at the withdraw. This was a path drop, not a controller failover, and Pure and HPE have
been asked what GFS2 should use.

- **MP-03 judges `no_path_retry` on its own.** `fail` or `0`: WARN, as Pure's written value with the trade-off stated.
  A finite number: PASS, showing the computed tolerance (about 20 s + N x `polling_interval`), and WARN above the new
  limit. `queue`: FAIL. Unset or unreadable: FAIL. A WARN raises no alarm, so hosts on `fail` get no new alarm.
- **MP-03 reads the value maps really get.** The order is `overrides`, device entry, `defaults`. The probe now reads
  `polling_interval` and `no_path_retry` from `defaults` and `overrides`; before, a value set in either was invisible.
- **Fix: MP-03b no longer treats a healthy finite retry as queueing for ever.** `12 chk` failed the check. Only `on`
  queues for ever. It now also reports `N sec` (the map has no usable path right now) and `off` on a finite map with no
  usable path (its retries ran out), and warns when a live map differs from the configured value.
- **New settings:** *Multipath no_path_retry* (what Remediate writes, default `0`, Pure's value; 0 or 1-60, anything
  else counts as 0) and *Multipath tolerance warning (seconds)* (default 110, inside HVM's all-paths-down and host-offline
  clocks). Changing a setting alters no host until someone runs Remediate on it.
- **Remediate never takes a host's finite `no_path_retry` back to 0**, including one set in `defaults`, which the old
  drop-in would have replaced without saying so. A finite setting that differs from the host's finite value is written,
  and MP-03 offers Remediate for it. The job log says when it keeps a value, and when an `overrides` entry makes the
  drop-in value moot.
- Remediate is offered for MP-03 and MP-03b only when it would change something, and not for a value set in
  `overrides`, which a drop-in cannot change.
- Docs: `replacement_timeout` does not control failover on multipathed iSCSI, because multipathd writes
  `fast_io_fail_tmo` into each session's `recovery_tmo`. The research notes said otherwise and are corrected.

Verified: the new probe section reads a real `multipath -t` on 26.04 (multipath-tools 0.14.3) and 24.04 (0.9.4); on a
26.04 test host, `hvmcli storage multipath apply` rewrote `/etc/multipath.conf` (twice, once with its own PURE entry at
`no_path_retry 3`) and the conf.d drop-in's values stayed effective throughout; on a test appliance the settings show
their defaults and a live Check reads `fail` hosts as WARN without a Remediate. Not verified: a real controller
failover, a finite value whose retries run out, what resets the host after the withdraw, and the other hosts while the
reset host rejoins. See BACKLOG.md.

## 0.11.1 (2026-10-26)

- The plugin links to its source and issue tracker on GitHub (Morpheus plugin page), and releases carry the plugin
  jar. Source: https://github.com/nburnett-io/pure-flasharray-morpheus-plugin

## 0.11.0 (2026-10-26)

**Prepared for publication.**

- Package `org.purehvm` (was a company package), author "Pure FlashArray for HVM contributors", Apache License 2.0
  (LICENSE, NOTICE).
- **New setting: Array tag namespace** (default `org.purehvm`). The plugin recognises its datastores and recovery
  clones by the tags it writes on the array, in this namespace only.
  **Upgrade note:** an install upgraded from an earlier build must set it to the namespace its volumes already carry,
  otherwise those datastores show as "created outside the plugin" until it is set.
- Tests and documentation use made-up names, addresses and identifiers only.
- Fix (tests): the built-jar check loaded the oldest jar in a shared build directory instead of the newest.

## 0.10.0

- **Permission split.** New role permission *Pure FlashArray Administration* for every storage change: link and
  unlink arrays, host groups, add/mount/rescan hosts, remediate, IQN sync, remove stale array hosts,
  create/grow/clone/adopt/share/retire datastores, delete restore leftovers. *Operations: Full* now means snapshot
  recovery only; *Operations: Read* views everything. The tab hides storage-changing buttons from users without
  Administration, and Morpheus refuses those routes.
- **Snapshot policies (read only).** The protection groups covering each datastore (directly, through the cluster's
  host group or a host), schedule, retention, replication, retention lock and last snapshot, plus the array-wide
  SafeMode setting. Customers see one line on their Restore tab about how often restore points are taken and how long
  they are kept.
- **Automatic compliance check with Morpheus alarms**, every *Automatic compliance check (hours)* (default 24, 0 =
  off). One alarm per failing host, replaced when the failures change, cleared when the host passes.
- **Reclaim space.** Restore leftovers (`.pre-restore-*` disks, file-restore copies) per GFS2 datastore, deletable
  after every host's libvirt is checked again; other files and detached Morpheus disks are listed, never deleted.

## 0.9.x

- MTU failures say how to fix them: the exact `hvmcli virtswitch edit` command, or which netplan file to edit, and
  whether a failing jumbo-frame ping is the host's or the switch's fault. MTU is never changed automatically.
- Code review and clean-up: shared helpers, a single compliance renderer, customer-safe error messages.
- Fixes: the cluster tab was hidden from the tenant owning the cluster; Retire's drain never ran (Morpheus replaces a
  request parameter named `action`); a failed file listing under `pipefail` could make a full datastore look empty.

## 0.7.x - 0.8.x

- **Customer self-service restore** on the instance page: overwrite the whole VM or chosen disks, file recovery
  (a temporary copy hot-attached to the running VM), add a copy as a new disk, clone to a new VM. Restore points are
  times only; customers never see snapshot, array, datastore or host names.
- Restore points are filtered by what each snapshot really holds (a per-snapshot index).
- Clones stay stopped and disconnected unless *Power on automatically* / *Connect to network automatically* are ticked.
- File-recovery copies are removed automatically after 24 h by default, at most 72 h (settings).

## 0.5.x - 0.6.x

- **Retire a datastore** end to end: hand over HA heartbeat and NVRAM roles, drain, move VMs with Morpheus Reconfigure,
  delete in Morpheus, remove the LUN from the hosts, destroy it on the array (never eradicated).
- Multi-tenant model: operator tenants (master, the cluster's owner, a setting) vs customer tenants; datastore tenant
  sharing in-process; audit log entries for every change.
- Recovery sessions rebuilt from array tags after a reload and closed automatically after a timeout.
- Hosts table: real IQNs vs Morpheus records, add a host to the host group, mount datastores on a new host, rescan,
  remove stale array hosts.

## 0.1.x - 0.4.x

- FlashArray REST 2.x client: version negotiation, token login (or a storage_admin service user created from an admin
  login), paging, certificate pinning.
- Host-group planner and applier; several arrays per cluster and several clusters per array.
- Host compliance probe and remediation against Pure's Linux iSCSI and multipath recommendations.
- GFS2 datastores: create (array volume, host group, every host verified, then Morpheus's own GFS2 type), grow,
  adopt existing datastores, capacity refresh.
- Snapshot recovery sessions without touching production: snapshot clone mounted read-only on one host under a
  private lock table, restore disks as new files or VMs in place.
