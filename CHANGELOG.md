# Changelog

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
