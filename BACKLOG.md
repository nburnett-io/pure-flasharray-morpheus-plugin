# Backlog

Open work only, roughly in priority order. What is done is in CHANGELOG.md.

1. **`no_path_retry` on GFS2: vendor answer and failover test.**
   - Question for Pure and HPE: what should `no_path_retry` be on GFS2? The default of the *Multipath no_path_retry*
     setting follows their answer; if they answer 0, *Multipath: allow lowering to 0* takes hosts back to it.
   - Test on a test array with a real controller failover and a timing host: the longest all-paths window. So far only a
     40 s path drop on a nested host (`fail` withdraws GFS2 and the host is reset; `12` holds). Still open: a finite
     value whose retries run out, and the other hosts while a reset host rejoins.
   - The withdrawn node's GFS2 journal is replayed only when that node remounts, not by the survivors. A cluster-level
     matter for the HVM Cluster Health plugin.
2. **Prove the host-join path on a real host** joining with no FlashArray sessions: the wait for its iSCSI networking,
   the removal of dead iface records, sessions, host group, rescan.
3. **Run the 0.13.5 features that have only been unit-tested** on a test cluster: deleting a dead iface (ISC-07), the
   `-old` host-group rename, removing a leftover recovery clone, and application-consistent snapshots against a VM with
   the qemu guest agent and one without.
4. **Duplicate sessions (ISC-03c).** A host logged in to each portal on both `default` and a virtual-switch iface has
   twice the paths over the same NICs. Decide whether the plugin should offer to log the extra ones out (a
   maintenance-window action).
5. **Storage server sync.** Sync array hosts, host groups and volumes into `StorageHost` / `StorageHostGroup` /
   `StorageVolume` (plugin API 1.5.0 has the models, services and SyncTask). Needs a design pass first: no public plugin
   does this for a storage server, which fields the Storage pages need is unknown, and volume records may offer Morpheus
   volume actions the plugin does not implement.
6. **Capacity and performance.** Array volume space and performance (`/volumes/space`, `/volumes/performance`) on the
   tab.
7. **Auth.** Purity OAuth2 API-client (JWT) as an alternative to API tokens.
8. **ActiveCluster / ActiveDR.** Pod placement, preferred arrays and the multipath requirements (two prio groups).
9. **LUN-per-vDisk datastore type** (MvmProvisionFacet), for per-VM array snapshots and QoS. About 5-10x the work of
   the current design; revisit once HPE re-lands a GFS2/volume-group plugin API.
10. **Pure1 / VM Analytics.** Ask Pure whether Pure1 VM Analytics can ingest an HVM (KVM) topology. The collector is
    vCenter-only.
11. **More workflow tasks** (create LUN, grow, recovery), only if someone needs them in a workflow.

Parked: a cluster package (`ComputeTypePackageProvider`). The plugin does not own the HVM cluster layout, and HVM 2.0
plus the plugin's host-join handling already set up a new host.
