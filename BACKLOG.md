# Backlog

Roughly in priority order.

0. **`no_path_retry` on GFS2: what is still unproven.**
   - Question for Pure and HPE: what should `no_path_retry` be on GFS2? The default of the *Multipath no_path_retry*
     setting follows their answer. If they answer 0, *Multipath: allow lowering to 0* (0.13.5) takes hosts back to it.
   - Test on a test array with a real controller failover and a timing host: the longest all-paths window. Done so far
     on a nested host with a 40 s drop of its one path: `fail` withdraws GFS2 at the first failed journal write (15 s in)
     and the Morpheus agent reboots the host 1 min 46 s later; `12` holds and resumes. Still open: a finite value whose
     retries run out, and the other hosts while the rebooted host rejoins.
   - The withdrawn node's GFS2 journal is not replayed by the survivors (the node leaves the lockspace cleanly); it is
     replayed only when that node remounts. A cluster-level matter for the HVM Cluster Health plugin.
   - The finite value must fit the host's `polling_interval`: tolerance is about 20 s + N x polling_interval, and hosts
     built by hand can have 10 instead of 5. MP-03 warns above the tolerance setting.
   - Done in 0.13.5: lowering option, job log for an out-of-range setting, `multipaths {}` entries (MP-03c), no `blkid`
     on a map without a usable path.
1. **Cluster membership events.** ADD_WORKER sets up the host's iSCSI and adds it to the host group; since 0.13.5 it
   waits (up to 10 minutes) for the host to reach a portal, removes dead iface records first (ISC-07), and REMOVE_WORKER
   writes an audit entry. Still open:
   - Prove it on a real host joining with no FlashArray sessions.
   - REMOVE_WORKER never removes the array host (it may still have GFS2 mounted); removal stays manual.
2. **Storage server sync.** Sync array hosts, host groups and volumes into `StorageHost` / `StorageHostGroup` /
   `StorageVolume` (plugin API 1.5.0 has the models and services, and SyncTask). Not started: no public plugin does this
   for a storage server, which fields the Storage pages need is unknown, and volume records may offer Morpheus volume
   actions the plugin does not implement. Needs a design pass and a test of what the pages show and allow.
3. **Stale recovery sweeper.** Done in 0.13.5: clones without an expiry get one; clones no linked cluster owns are
   listed (Leftover recovery clones) with a safe Remove.
4. **Persistent jobs.** Done in 0.13.5: finished jobs are kept in the cluster's config (last 15). Plugin API 1.5.0 cannot
   put them in Morpheus's own process history (no process for a cluster or storage server). A job running during a
   restart still loses its progress view.
5. **Application-consistent snapshots.** Done in 0.13.5, off by default. Not yet run against live guests: turn it on in a
   test cluster with a VM that has the qemu guest agent and one that has not.
6. **Protection groups.** Showing them is done (0.10.0, read only). Editing pgroups or default protection on create
   is not wanted: policies stay on the array. Nothing to do.
7. **Restore to instance.** Covered by the instance Restore tab since 0.7: overwrite, add as a new disk, file recovery
   and clone to a new (stopped) VM go through Morpheus. The operator recovery session's "restore as new file" stays the
   expert path.
8. **Task providers.** Done in 0.13.5 for snapshots ("Pure FlashArray snapshot" workflow task, verified on a test
   appliance). Create LUN, grow and recovery as tasks: only if someone needs them in a workflow.
9. **Cluster package.** `ComputeTypePackageProvider` exists in 1.5.0 and can target HVM (MVM) clusters, but the plugin
   does not own the HVM cluster layout, and since HVM 2.0 builds the iSCSI networking and initiator name itself and the
   plugin sets up a joining host (item 1), a package adds little. Parked.
10. **Capacity and performance.** Array volume space and performance (`/volumes/space`, `/volumes/performance`) on the
    tab.
11. **Auth.** Purity OAuth2 API-client (JWT) as an alternative to API tokens.
12. **ActiveCluster / ActiveDR.** Pod placement, preferred arrays and the multipath requirements (two prio groups).
13. **LUN-per-vDisk datastore type** (MvmProvisionFacet), for per-VM array snapshots and QoS. About 5-10x the work of
    the current design; revisit once HPE re-lands a GFS2/volume-group plugin API.
14. **Pure1 / VM Analytics.** Ask Pure whether Pure1 VM Analytics can ingest an HVM (KVM) topology. The collector is
    vCenter-only.
15. **Duplicate sessions (ISC-03c).** A host logged in to each portal on both `default` and a virtual-switch iface has
    twice the paths over the same NICs. Decide whether the plugin should offer to log the extra ones out (disruptive
    only if done wrong; a maintenance-window action).
