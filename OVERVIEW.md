# Pure FlashArray for HVM: overview

**Status:** tested on Morpheus 9.1 with one FlashArray (Purity 6.10); see the Status table at the end and
[CHANGELOG.md](CHANGELOG.md). Validate it in a test environment before production.

## Why this exists

When we move from VMware to HPE Morpheus Enterprise 9.1 with HVM hosts on iSCSI, we lose Pure's vSphere tooling. The
"Pure Storage VMware Appliance" OVA (`pure-vmware-appliance-latest-signed.ova`, 5.0.0) is only an Ubuntu Docker host.
Its three roles are apt packages from `deb.cloud-support.purestorage.com`:

| Package | What it is | HVM equivalent |
|---|---|---|
| `pure-vsphere-remote-plugin` 5.6.1 | Spring Boot backend plus an Angular UI inside vSphere: datastores, host groups, snapshots, recovery, vVols | **this plugin** |
| `vm-analytics-collector` 3.7.0 | Go collector that sends vCenter topology and performance data to Pure1 VM Analytics | none. Pure1 would have to accept a KVM topology; that is a question for Pure |
| `pure-vmware-cloud-manager` | VMC/AVS tooling | not applicable |

Pure publishes iSCSI and GFS2 best-practice guides for HPE VME, but no plugin. The research behind the design is in
`docs/research/`.

## What it does, mapped to the requirements

| Requirement | How |
|---|---|
| Create or verify host iSCSI settings against best practice | **Compliance** tab section. A read-only probe on every host is checked against about 30 rules (table B2 in `docs/research/07-*`). **Remediate** builds a per-host script that writes `/etc/multipath/conf.d/pure.conf`, `/etc/udev/rules.d/99-pure-storage.rules` and ARP sysctls (only when NICs share a subnet), sets `node.startup=automatic` and NOP-Out to 5/5, and binds one iSCSI iface per storage NIC, logging in only the missing same-fabric sessions. |
| Create the cluster and hosts on the array | **Host group** section. `HostGroupPlanner` matches nodes to array hosts by IQN, picks or creates the host group (named after the cluster), creates any missing hosts (named by short hostname, with Pure's `-iscsi` collision suffix) and adds them. The plan is shown before it is applied. Conflicts, such as a node already in another host group, block the plan; nothing is moved automatically. |
| Map storage | **Create GFS2 datastore.** See the next section. |
| SAN snapshot restore without affecting the production datastore | **Snapshot recovery.** Production is only ever read. Restores land as new files or as a new datastore. |

## Creating a GFS2 datastore: who does what

Morpheus 9.1 already has a native GFS2 datastore type, *HPE Clustered Datastore (Shared LUN)*. Its create step runs
`mkfs.gfs2 -p lock_dlm -j 32`, joins DLM/Corosync, mounts the filesystem on every host at `/mnt/<uuid>`, defines the
libvirt pools, and owns HA heartbeat and fencing through the Morpheus agent's quorum service. **The plugin does not
re-implement any of that.** Doing so would fight Morpheus's cluster stack. HPE tried exactly that for their Alletra
plugin, hit a deadlock, and reverted the plugin GFS2 API in July 2026.

So "Create GFS2 datastore" is one server-side job:

1. Create the FlashArray volume, tag it (namespace from the *Array tag namespace* setting, default `org.purehvm`:
   cluster id, role, name), and connect it to
   the cluster's **host group**. Every host then gets the same LUN; no LUN ID is passed and the array chooses it.
2. On every host in parallel, rescan sessions and wait for `/dev/mapper/3624a9370<serial>`. Every host must report the
   same size. This step matters because Morpheus silently hides a block device from its picker if even one host lacks
   it.
3. Hand the device path to Morpheus's GFS2 type through a `Gfs2Registrar`:
   - **in-process** (default since 0.4.3, verified live): builds the Datastore record the way the UI does and calls
     `morpheus.services.storage.datastoreType.createDatastore()`. The record has the cluster's tenant as owner,
     `uniqueId` = UUID, `externalPath=/mnt/<uuid>` and `config.blockDevice`. It needs no token.
     - **Hibernate session:** 9.1 core's createDatastore and refreshDatastore need a session on the calling thread,
       which plugin job threads lack ("Could not obtain current Hibernate Session"). `MorpheusSession` opens one
       through GORM `withNewSession` on core's `com.morpheus.Datastore` class. That is the one place the plugin
       relies on a Morpheus internal.
     - **Cleanup:** if core refuses, the record is removed before the LUN is rolled back.
   - **rest**: `POST /api/clusters/{id}/datastores` with `config.blockDevice` and `allowReformat:false`, the same
     request the UI makes. The token must belong to a user in the tenant that **owns the cluster**. A master-tenant
     token can GET a subtenant's cluster, but this POST answers 404 "Cluster not found", and no parameter or header
     changes that.
4. Tag the volume with the Morpheus datastore id.

If any step fails, the job rolls back: hosts are cleaned first (`multipath -f`, then the path devices are deleted),
then the array (disconnect, destroy, eradicate; the volume is brand new and holds no data). Host cleanup comes before
the array disconnect because Ubuntu 22.04/24.04 leaves stale maps behind when the array disconnects first.

**Grow:** array resize (refuses to shrink) → every host rescans its paths and runs `multipathd resize map` → all hosts
must agree on the new size → `gfs2_grow` runs once on one host → Morpheus cluster refresh. Morpheus 9.1 has no grow
action of its own.

## Snapshot recovery

Both flows follow what Pure's vSphere plugin does for VMFS (copy the snapshot, re-signature, register, move back), adapted
to GFS2. The full reasoning is in `docs/research/07-gfs2-clone-and-linux-best-practice.md` §A.

- **Recovery session (default).**
  1. Snapshot → new volume `<vol>-rcv-<tag>`, connected to **one host only**.
  2. Mount it `lockproto=lock_nolock,locktable=recover:<tag>` read-write, which replays the crash-consistent journals,
     then remount read-only.
  3. Browse the disk files, then **Restore as new file** copies a disk into `<prod datastore>/restored/`. The copy
     re-points the qcow2 backing file into the clone, because overlays on the clone reference production's base image
     by absolute path.
  4. **Close**: host cleanup, then array disconnect, destroy and eradicate.

  Why the private lock table: the kernel names `/sys/fs/gfs2/<locktable>` after it, so mounting a clone with
  production's table fails on any host that already mounts production, even with lock_nolock. On a host that doesn't
  mount production, a lock_dlm mount would join production's DLM lockspace and corrupt both.
- **Clone as new datastore (experimental, setting `enableExperimental`).** Snapshot → new volume → one host →
  `tunegfs2` sets a new lock table (same cluster id, new fs name) and a new UUID. It checks that the change actually took,
  because of a known tunegfs2 bug. It replays the journals locally and moves the stale `mvm-hb` heartbeat directory
  aside. The clone is then presented to the host group, every host is verified, and Morpheus is asked to adopt it with
  `allowReformat:false`. Whether Morpheus adopts an existing GFS2 filesystem has not been verified yet.

Snapshots are crash-consistent: they are equivalent to every host losing power at once, and GFS2 replays its journals.
For application consistency, freeze the guests (`virsh domfsfreeze`) around the snapshot; that is on the backlog. Never
freeze the host GFS2 filesystem: the freeze is cluster-wide and stalls Morpheus heartbeats, and after about 2 minutes
non-pinned VMs shut down.

## Safety model

- Every array-changing or host-changing action needs **Pure FlashArray Administration**; snapshot recovery needs
  **Operations: Full**, viewing **Operations: Read**. Changes run as a job with a visible step log, and only one job
  per cluster runs at a time.
- Snapshot policies (protection groups, SafeMode) are read only: set on the array, never from Morpheus.
- The tab can only operate on volumes this plugin tagged for *that* cluster.
- **Several arrays, several clusters.** A cluster keeps a list of links (array plus host group). Any number of clusters
  can share an array, but never a host group on it: two corosync clusters seeing the same GFS2 LUN would corrupt it.
  Linking, applying a host group and adding a host all check every other cluster's links first.
- Host scripts validate every argument: WWIDs match `3624a9370[0-9a-f]{24}` and paths are restricted to safe
  characters. They run through the Morpheus agent, never over direct SSH.
- Never done: `multipath -F`, iSCSI logout, restarting corosync or dlm, `fsck` on production, rewriting
  `/etc/multipath.conf`, overwriting a production volume or disk file. The multipath drop-in is refused while any GFS2
  filesystem is mounted through an `mpathX` alias.
- **Secrets:** the array API token is stored by Morpheus (the storage server's encrypted password field). The array
  certificate is pinned by SHA-256, automatically on first use unless a fingerprint is entered; there is no "trust
  everything" option. Pure's plugin trusts every certificate and
  encrypts its store with a default passphrase of `password`; we copied neither.

## Parity with Pure's vSphere plugin

| Pure vSphere feature | Here |
|---|---|
| Add array (URL and credentials) | Storage Server type with token and pinned certificate |
| Add/Update Host Groups wizard (plan, then apply) | Host group section |
| Configure iSCSI on hosts (SendTargets, LoginTimeout 30, NoopInterval 5) | Compliance and remediation; per-NIC iface binding is stricter than Pure's ESXi logic |
| Create datastore (volume → host group → rescan → VMFS) | Create GFS2 datastore |
| Edit or expand datastore | Grow |
| Snapshots tab (create, list including pgroup snapshots) | Snapshots |
| Copy snapshot to new datastore (resignature) | Clone as new datastore (experimental) |
| Recover VMs from snapshot (temporary datastore → register → move back) | Recovery session → restore disk as new file |
| Restore datastore in place (overwrite) | **Not offered.** It contradicts "without affecting prod" |
| Protection groups, SafeMode default protection | Shown read-only (Snapshot policies); never created or changed from Morpheus |
| Capacity and performance tabs, Pure1 load | Backlog |
| vVols, VASA, SPBM, Replication Manager, Teleport | Not applicable to KVM |

## Status

Tested on: Morpheus 9.1, an HVM cluster of two hosts on Ubuntu 24.04/26.04, one FlashArray (Purity 6.10.4).

| Area | Built | Unit-tested | Verified live |
|---|---|---|---|
| FlashArray REST client, service-user bootstrap, cert pinning | yes | yes (fake transport) | yes |
| Host-group planner and applier | yes | planner yes | reuse of an existing host group by IQN, yes; creating one, no |
| Compliance probe and checks | yes | yes (sample probe output) | probe and checks run on both hosts, yes |
| Remediation | yes | script content | no |
| Adopting existing datastores | yes | yes | yes |
| Grow (array, rescan, `gfs2_grow`, Morpheus capacity) | yes | yes | yes |
| Provision and rollback | yes | yes (ordering asserted) | no |
| Recovery session, restore disk, restore VM in place | yes | yes (orchestration) | yes (a test VM) |
| Clone as datastore | yes (experimental) | script content | no |
| Hosts table, add to host group, mount on a new host, ADD_WORKER | yes | script content | table yes; adding a host, no (waiting on a new host) |
| Several arrays per cluster, several clusters per array | yes | links, legacy migration, resolve, guard, adoption merge | single-link migration and tab, yes; a second array, no (tested with one) |
| Morpheus wiring (storage server, tab, controller) | yes | API contract against plugin-api 1.5.0 | yes |
