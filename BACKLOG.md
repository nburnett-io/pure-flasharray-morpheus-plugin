# Backlog

Roughly in priority order.

0. **`no_path_retry` on GFS2: what is still unproven (0.12.0).**
   - Question for Pure and HPE: what should `no_path_retry` be on GFS2? The default of the *Multipath no_path_retry*
     setting follows their answer.
   - Test on a test array with a real controller failover and a timing host: the longest all-paths window. Done so far
     on a nested host with a 40 s drop of its one path: `fail` withdraws GFS2 at the first failed journal write (15 s in)
     and the host is reset about 2 minutes later; `12` holds and resumes. Still open: a finite value whose retries run
     out, what resets the host (its journal just stops; no shutdown, no watchdog device), and the other hosts while the
     reset host rejoins (corosync on a survivor logged a token timeout and a 72 s consensus wait).
   - Done on a 26.04 test host (multipath-tools 0.14.3): after `hvmcli storage multipath apply`, twice, once with a
     PURE entry in the staged file, `/etc/multipath/conf.d/pure.conf` was untouched and `multipath -t` still showed its
     values. Not repeated on 24.04 (0.9.4), where the directory is compiled in too.
   - Decide how a host goes back to 0 if the vendors answer 0: Remediate never lowers a finite value, so today that means
     editing the drop-in by hand. An explicit "allow lowering to 0" option is the likely answer.
   - The job log does not say when an out-of-range setting (say `90` or `queue`) was treated as 0; the help text does.
   - The probe does not read a `multipaths {}` section, which beats `overrides` for one WWID.
   - During an all-paths window on a finite map, `blkid` in the probe blocks until the window ends, because it reads the
     device (with `queue`, for ever). Skip maps with no usable path if that proves a problem.
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
