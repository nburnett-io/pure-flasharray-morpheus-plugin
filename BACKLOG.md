# Backlog

Roughly in priority order.

1. **Cluster membership events.** Handle `ClusterEvent.ADD_WORKER` / `REMOVE_WORKER`: add the new node's IQN to the
   host group before Morpheus auto-logs in to the iSCSI targets, and remove it on node removal. The event signature is
   `void onEvent(E, AccountIntegration)`.
2. **Storage server sync.** In `refreshStorageServer`, sync array hosts, host groups and volumes into
   `StorageHost` / `StorageHostGroup` / `StorageVolume` with SyncTask, so they show under Infrastructure > Storage.
3. **Stale recovery sweeper.** Find `role=recovery-clone` volumes older than N hours (tag `created`) whose host has no
   mount, and offer cleanup.
4. **Persistent jobs.** Job state is in memory today. Persist it, or use Morpheus process events
   (`morpheus.services.process`), so the history survives an appliance restart.
5. **Application-consistent snapshots.** `virsh domfsfreeze` on all VMs on the datastore across hosts in parallel, then
   a protection-group snapshot, then thaw. Never freeze the host filesystem.
6. **Protection groups.** Showing them is done (0.10.0, read only). Editing pgroups or default protection on create
   is not wanted: policies stay on the array.
7. **Restore to instance.** After "restore disk as new file", attach the file to a stopped copy of the VM through
   Morpheus instead of by hand.
8. **Task providers.** Workflow tasks for create LUN, grow, snapshot and recovery, for automation and approvals.
9. **Cluster package.** `ComputeTypePackageProvider` "Pure iSCSI" so new clusters get the host baseline at build time.
10. **Capacity and performance.** Array volume space and performance (`/volumes/space`, `/volumes/performance`) on the
    tab.
11. **Auth.** Purity OAuth2 API-client (JWT) as an alternative to API tokens.
12. **ActiveCluster / ActiveDR.** Pod placement, preferred arrays and the multipath requirements (two prio groups).
13. **LUN-per-vDisk datastore type** (MvmProvisionFacet), for per-VM array snapshots and QoS. About 5-10x the work of
    the current design; revisit once HPE re-lands a GFS2/volume-group plugin API.
14. **Pure1 / VM Analytics.** Ask Pure whether Pure1 VM Analytics can ingest an HVM (KVM) topology. The collector is
    vCenter-only.
