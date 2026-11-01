# Changelog

## 0.12.1 (2026-11-01)

- Wording only: MP-03's hint, the *Multipath no_path_retry* help text and the docs now describe the path-drop test
  (withdraw and host reset observed on a test host) instead of a reasoned chain. No check or remediation logic changed.

## 0.12.0 (2026-10-31)

**`no_path_retry` on GFS2 clusters.** Failing I/O about 20 s into an all-paths outage withdraws GFS2 on that host, and
the host is reset about two minutes later. Seen on a nested HVM 2.0 test host (Ubuntu 26.04, one iSCSI path dropped
for 40 s): at `fail`, `session recovery timed out` at 15 s, then `Error -5 writing to journal` and `file system
withdrawn` at once, and a reset two minutes after that; at `12` the same drop was held and I/O resumed with no error.
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
