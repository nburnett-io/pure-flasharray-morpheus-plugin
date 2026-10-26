# 03 — GFS2 snapshot-clone mounting ("resignature") and Linux/Pure iSCSI host best practices

Research date: 2026-10-20. Scope: Morpheus HVM / HPE VM Essentials (VME) clusters on Ubuntu 22.04/24.04 with Pure (Everpure) FlashArray over iSCSI.

Confidence labels used below:
- **[DOC]**: stated in a vendor doc, man page or kernel source (cited).
- **[SRC]**: read directly from upstream source code (Linux v6.8 `fs/gfs2/*`, multipath-tools `hwtable.c`).
- **[INF]**: my inference from the documented behaviour. It needs testing before production use.

---

## Part A — Mounting a FlashArray clone of a GFS2 datastore next to production

### A1. How Morpheus/VME builds a GFS2 datastore

| Item | Finding | Conf. | Source |
|---|---|---|---|
| Cluster stack, layouts 1.3 / 2.0 (current) | Corosync handles membership and messaging, and DLM handles GFS2 locks. The **Morpheus Agent (`morphd` QuorumCheckService)** makes the quorum, fencing and recovery decisions. **No Pacemaker and no `pcs`.** Corosync "Quorate: No" is ignored. The Agent is reached at `https://localhost:7443/quorum`. | DOC | [9.1 Architecture](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/architecture.html), [9.1 Troubleshooting](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/troubleshooting.html) |
| Cluster stack, pre-1.3 "Legacy" layouts | Pacemaker + Corosync + DLM. Resources are `dlm-clone`, `<name>-clone` (an `ocf:heartbeat:Filesystem` clone) and the STONITH device `hpevm_gfs2_scsi` (`fence_scsi_hpevm`, SCSI-3 PR, `key_value=hash`, `provides=unfencing`). To find your layout, run `pcs status`: if those resources appear, the layout is pre-1.3. | DOC | [HPE "Creating a GFS2 Datastore Manually" (VME 9.0.2)](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008401en_us&page=GUID-8D4F622F-4A39-48BC-B600-06141C1E88E5.html&docLocale=en_US) |
| mkfs | `mkfs.gfs2 -O -K -p lock_dlm -r 2048 -t <cluster_id>:<resourcename> -j 32 <device>`. Here `-K` skips discard at mkfs time and `-r 2048` sets the resource-group size to 2048. | DOC | same HPE page; [9.1 Storage Lifecycle](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/storage_operations.html) |
| Lock table | `<cluster_id>:<resourcename>`. Both parts are lowercase. `<resourcename>` is the sanitized datastore name, 30 characters or fewer (example `my_datastore_a1b2c`). HPE says the Morpheus path "ensures locktable naming consistency". | DOC | HPE manual-creation page |
| Journals | 32 at creation. `gfs2_jadd` adds more if the cluster has more hosts than journals. | DOC | 9.1 Storage Lifecycle |
| Mount options | The pre-1.3 Pacemaker resource uses `options="noatime,nodiratime,discard"`. On 1.3/2.0 layouts the Agent mounts the filesystem, and the options are not documented. Read them from `/proc/mounts`. | DOC / INF | HPE manual-creation page |
| Mount point | `/mnt/<uuid>`, for example `/mnt/dddddddd-4444-4555-8666-777777777777`. Managed mounts are recorded in `/opt/morpheus-node/.mounts`. | DOC | HPE manual-creation page; 9.1 Storage Lifecycle |
| Block device | Always the WWN path `/dev/mapper/3<wwn>`, never `mpathX`. For Pure that is `/dev/mapper/3624a9370<24-hex serial>`. | DOC | 9.1 Storage Lifecycle; [Everpure FC VME-Specific Considerations](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-hpe-vme-specific-considerations) |
| libvirt pool | A libvirt storage pool is created on every host. The manual procedure says to `virsh pool-define-as / pool-start / pool-autostart` "specifying the GFS2 mountpoint as the target path", which means a **dir-type pool**. The pool name is not documented, so check with `virsh pool-list --all --details` and `virsh pool-dumpxml`. | DOC / INF | HPE manual-creation page |
| VM disks | qcow2 files on the shared filesystem. A file-based snapshot adds an **external qcow2 overlay** "alongside the original disk image". Linked clones are qcow2 overlays whose backing file is a snapshot. The naming is not documented. A third-party tool shows local disks at `/var/morpheus/kvm/local/<vm>/<vm>.qcow2`, which suggests `/mnt/<uuid>/<vm>/<disk>.qcow2` on GFS2 [INF]. Always resolve the real path with `virsh domblklist <dom> --details`. | DOC / INF | [9.1 Snapshots](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/snapshots.html); [builtbyfood/morpheus-unmanaged-vm-disk-migrate](https://github.com/builtbyfood/morpheus-unmanaged-vm-disk-migrate) |
| Heartbeat files on the datastore | Each host writes `hb.properties` plus **the VM XML definitions** every 20 s to `<datastore>/mvm-hb/<cluster_uuid>-2/<host_hash>/`. APD protection: after 6 failed heartbeat writes (about 2 minutes), non-pinned VMs on shared storage shut down. | DOC | 9.1 Architecture |
| Limits / caveats | Maximum 50 GFS2 datastores (25 on stretch clusters). Ubuntu 22.04 needs the HWE kernel for GFS2. The 24.04 kernel (6.8) has a GFS2/DLM defect that can surface after stretch failover (`validate_lock_args -22`). The upstream fix is `b6900ce15191` (Linux 6.13), included in HVM OS 26.04. Morpheus warns: do not use `fsck.gfs2`, recreate, or cluster-wide unmount **production** datastores as improvised recovery. | DOC | 9.1 Troubleshooting; Everpure FC VME-Specific Considerations |

### A2. What breaks if a block clone of a mounted GFS2 is presented to the same cluster

A FlashArray `purevol copy` of a snapshot is a new volume. It gets a new serial, so its multipath WWID (`3624a9370<serial>`) differs automatically. **Everything inside the volume is identical to production**: the superblock lock table, the fs UUID, and 32 journals, of which every journal belonging to a mounting host is dirty.

1. **Same lock table means a sysfs and DLM collision.** The kernel names the per-filesystem sysfs kobject `/sys/fs/gfs2/<locktable>` (`gfs2_sys_fs_add()` → `kobject_init_and_add(..., "%s", sd_table_name)`). The lock table comes from the superblock unless you pass `locktable=` at mount time [SRC: `ops_fstype.c init_names()`, `sys.c`]. Two outcomes follow:
   - **On a host that already mounts production:** the clone mount fails, because the duplicate sysfs name gives `-EEXIST`. This applies **even with `lockproto=lock_nolock`** unless you also pass a different `locktable=` [SRC; INF on the exact error text]. This is the common surprise.
   - **On a cluster host that does *not* currently mount production:** the lock_dlm mount joins the *existing cluster-wide* DLM lockspace `<fsname>` (`dlm_new_lockspace(fsname, cluster, ...)` [SRC `lock_dlm.c`]). Two different block devices then share one lock namespace and one journal-ID arbitration, which almost certainly corrupts both. The gfs2(5) man page states that wrong locking on a multi-node fs "will almost certainly result in filesystem corruption", and Red Hat requires the fsname to be "unique for all lock_dlm file systems over the cluster" [DOC: [gfs2(5)](https://manpages.debian.org/testing/gfs2-utils/gfs2.5.en.html), [RHEL 8 Configuring GFS2](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/8/html/configuring_gfs2_file_systems/assembly_creating-mounting-gfs2-configuring-gfs2-file-systems)].
   - The cluster-name half of the lock table must equal the corosync `cluster_name`, or a lock_dlm mount is refused. This is not a risk for a same-cluster clone, but it matters when you mount the clone on a *different* cluster [SRC `lock_dlm.c`].
2. **Identical fs UUID.** `/dev/disk/by-uuid/<uuid>` flaps between the two devices, and anything keyed on UUID (blkid, udev, backup agents) can pick the wrong one. Morpheus keys on the WWN device and `/mnt/<uuid>` (a Morpheus UUID, not the fs UUID), so this is a hygiene problem rather than a corruption problem. Fix it anyway with `tunegfs2 -U` [INF].
3. **Dirty journals.** A snapshot of a live filesystem is crash-consistent: every mounting host's journal is dirty.
   - A **lock_nolock mount replays ALL journals**: `gfs2_lm_mount()` sets `ls_first = 1`, and the first mounter loops `gfs2_recover_journal()` over every jid [SRC `ops_fstype.c`].
   - A mount with `-o ro` **still replays** while the block device is writable ("recovery required on read-only filesystem / write access will be enabled during recovery"). If the device itself is read-only, the mount fails with `-EROFS` ("Can't replay: read-only block device") [SRC `recovery.c`].
   - A `spectator`/`norecovery` first mount on dirty journals fails, because `check_journal_clean()` rejects it [SRC; DOC gfs2(5)]. **Do not use `spectator` as the "safe" recovery mount on a fresh clone.**
4. **Pre-1.3 fencing.** `fence_scsi_hpevm`'s `devices=` list names only the production device, so a clone mounted cluster-wide outside Morpheus has **no STONITH coverage** [DOC: the HPE page requires the device in both `devices=` and `Filesystem device=`]. A copied Pure volume should carry no SCSI-3 PR registrations, because reservations belong to the volume object, not its data [INF]. Verify with `sg_persist -i -k` / `-i -r`. If keys are present, writes fail with RESERVATION CONFLICT.
5. **Stale Morpheus artifacts on the clone.** `mvm-hb/` holds old `hb.properties` and VM XML definitions [DOC 9.1 Architecture]. Never mark the clone as a heartbeat target. For workflow (ii), consider renaming `mvm-hb` on the clone before you register it [INF].
6. **qcow2 backing chains point at production.** Morpheus snapshot overlays and linked clones store an absolute backing-file path, which will be `/mnt/<prod-uuid>/...`. If you open the clone's overlay with `qemu-img` or libvirt, it silently reads the **production** base image [INF, from standard qcow2 semantics]. See the A3 commands for the fix.

### A3. Commands — tools and their exact semantics

| Tool | What it does | Source |
|---|---|---|
| `tunegfs2 -l <dev>` | Prints the superblock, including the lock table, lock protocol and UUID. | [tunegfs2(8)](https://manpages.debian.org/testing/gfs2-utils/tunegfs2.8.en.html) |
| `tunegfs2 -o locktable=<cluster>:<newfs> <dev>` | Rewrites the on-disk lock table. The superblock is read at mount time, so changes on a mounted fs "not recommended". On GFS2 the label *is* the lock table, so `-L <cluster>:<newfs>` is equivalent. | tunegfs2(8) |
| `tunegfs2 -U <uuid> <dev>` | Sets a new fs UUID (GFS2 only). | tunegfs2(8) |
| Known bug | On RHEL 7, `-o locktable=` silently did nothing. **Always re-read the value with `tunegfs2 -l` afterwards**, and fall back to `-L`. | [RH solution 4075151](https://access.redhat.com/solutions/4075151) |
| `mount -o lockproto=lock_nolock[,locktable=x:y]` | Overrides the on-disk protocol and table for one mount. For lock_nolock the table is "ignored" for locking, but it is still used as the sysfs name [SRC]. lock_nolock enables `localflocks`. | gfs2(5) |
| `fsck.gfs2 -n / -y` | The fs must be unmounted on **all** nodes. `-n` is read-only. `-p` is "safe" only for lock_nolock filesystems. | [fsck.gfs2(8)](https://manpages.debian.org/testing/gfs2-utils/fsck.gfs2.8.en.html) |
| Red Hat backup model | "Red Hat recommends making a GFS2 backup by creating a hardware snapshot on the SAN... The backup system should mount the snapshot with `-o lockproto=lock_nolock` since it will not be in a cluster." | RHEL 8 Configuring GFS2 §3.3 |

#### Workflow (i) — single-host lock_nolock "recovery mount" (recommended default)

Use this to copy one VM's disks out, or to import them as a new VM. Nothing touches DLM, corosync, the Agent or other hosts.

```bash
# --- Array (Purity CLI) ---
purevol snap --suffix restore1 <prod-vol>          # or: purepgroup snap --suffix restore1 <pgroup>
purevol copy <prod-vol>.restore1 <prod-vol>-r1     # pgroup: <pgroup>.restore1.<prod-vol>
purehost connect --vol <prod-vol>-r1 <ONE-host>    # ONE host, NOT the host group
purevol list <prod-vol>-r1                         # note Serial -> WWID = 3624a9370 + lower(serial)

# --- Recovery host ---
sudo iscsiadm -m session --rescan                  # Ubuntu 22.04/24.04: rescan explicitly; don't rely on TDR
WWID=3624a9370<serial-lowercase>
sudo multipath -ll $WWID                           # expect all paths active, PURE,FlashArray
sudo tunegfs2 -l /dev/mapper/$WWID | grep -Ei 'lock|uuid'   # proves it is a clone (same table as prod)
sudo sg_persist -i -k /dev/mapper/$WWID            # expect no registered keys

sudo mkdir -p /mnt/recover-r1
# rw mount: lock_nolock = first mounter, replays every dirty journal; distinct locktable avoids the sysfs clash
sudo mount -t gfs2 -o lockproto=lock_nolock,locktable=recover:r1,noatime /dev/mapper/$WWID /mnt/recover-r1
dmesg | grep -i 'gfs2.*jid'                        # journal replay messages
# Optional, after the replay: remount read-only to prevent accidental writes
sudo mount -o remount,ro /mnt/recover-r1

# Inspect the chain BEFORE copying (backing paths will point at /mnt/<prod-uuid>/...)
qemu-img info --backing-chain /mnt/recover-r1/<vm>/<disk>.qcow2
# Flatten from the CLONE's chain explicitly (do not let qemu follow the absolute prod path):
qemu-img convert -p -O qcow2 \
 'json:{"driver":"qcow2","file":{"driver":"file","filename":"/mnt/recover-r1/<vm>/<overlay>.qcow2"},
        "backing":{"driver":"qcow2","file":{"driver":"file","filename":"/mnt/recover-r1/<vm>/<base>.qcow2"}}}' \
 /mnt/<prod-uuid>/<restore-dir>/<disk>-restored.qcow2
# Alternative if the mount is rw: qemu-img rebase -u -F qcow2 -b /mnt/recover-r1/<vm>/<base>.qcow2 <overlay>
qemu-img check /mnt/<prod-uuid>/<restore-dir>/<disk>-restored.qcow2   # leaked clusters are harmless; -r leaks to fix
sudo chown libvirt-qemu:kvm /mnt/<prod-uuid>/<restore-dir>/<disk>-restored.qcow2
```

Getting the disk into Morpheus:
- **Option A (simple, managed):** provision a new VM in Morpheus with the same disk sizes, leave it powered off, find its paths with `virsh domblklist`, and `qemu-img convert` over them.
- **Option B:** upload the qcow2 as a Virtual Image (the image must include `metadata.json` per 9.1 Snapshots) and provision from it.

Do not hand-`virsh define` the old XML. HPE says manual virsh work on managed VMs is not needed and not supported [HPE community, via search].

#### Workflow (ii) — full cluster-wide second datastore after re-signature

```bash
# 1. Array: snapshot -> copy -> connect to ONE host only (as in workflow (i)).
# 2. On that host, with the clone mounted NOWHERE:
CL=$(sudo tunegfs2 -l /dev/mapper/$WWID | awk -F': ' '/table/{split($2,a,":");print a[1]}')   # cluster_id
NEW=<sanitized lowercase name <=30 chars, matching the datastore name you will create>
sudo tunegfs2 -o locktable=${CL}:${NEW} /dev/mapper/$WWID
sudo tunegfs2 -U $(uuidgen) /dev/mapper/$WWID
sudo tunegfs2 -l /dev/mapper/$WWID                 # VERIFY both changed (RH 4075151); else use -L ${CL}:${NEW}
# 3. Replay the journals once, locally:
sudo mount -t gfs2 -o lockproto=lock_nolock /dev/mapper/$WWID /mnt/recover-r1 && \
  sudo mv /mnt/recover-r1/mvm-hb /mnt/recover-r1/mvm-hb.from-snapshot   # [INF] avoid stale heartbeat/XML
sudo umount /mnt/recover-r1
sudo fsck.gfs2 -n /dev/mapper/$WWID                # optional read-only check (slow on large fs)
# 4. Array: present the volume to the host group (same LUN on all hosts)
purehgroup connect --vol <prod-vol>-r1 <hgroup>    # then remove the single-host connection
# 5. Every host: rescan and confirm the same WWID and size
sudo iscsiadm -m session --rescan; sudo multipath -ll $WWID
# 6. Morpheus UI: Cluster > Storage > Data Stores > Add > GFS2 Pool / "HPE Clustered Datastore (Shared LUN)"
#    Block device /dev/mapper/$WWID, ALLOW REFORMATTING **UNCHECKED**, HEARTBEAT TARGET unchecked.
```

Caveats for workflow (ii):
- **Adoption by Morpheus is unverified [INF].** The UI says reformatting happens only if "a GFS2 filesystem doesn't currently exist" [DOC HPE Adding GFS2 Pools], which implies an existing fs is kept. The docs do not say whether Morpheus checks or rewrites the lock table, so the `${NEW}` value must match the name Morpheus derives. **Test this before relying on it.**
- **Pre-1.3:** if Morpheus cannot adopt the clone, the documented manual path is the HPE `pcs` procedure. Skip its `mkfs` step and add the clone to the STONITH `devices=` list (the HPE page manages one `hpevm_gfs2_scsi` device). **1.3/2.0:** there is no documented manual path, so use the UI only. HPE says not to improvise with `pcs` or manual fence-ack on agent-managed clusters [DOC 9.1 Troubleshooting].
- The VMs on the clone are not known to Morpheus. Import them as in workflow (i), or copy their disks to the production datastore.

#### Workflow (iii) — cleanup (order matters: host side first, then the array)

```bash
# Per host that saw the clone:
sudo fuser -vm /mnt/recover-r1 ; sudo lsof +f -- /mnt/recover-r1     # nothing may hold it
virsh pool-list --all | grep -i recover && { virsh pool-destroy <p>; virsh pool-undefine <p>; }
sudo umount /mnt/recover-r1 && ls /sys/fs/gfs2/                       # recover:r1 entry must be gone
#   (workflow ii: delete the datastore in Morpheus first; it unmounts everywhere, destroys the pools, cleans .mounts)
PATHS=$(sudo multipath -ll $WWID | grep -oE 'sd[a-z]+' | sort -u)
sudo dmsetup info -c $WWID                          # Open count must be 0
ls /sys/block/$(basename $(readlink -f /dev/mapper/$WWID))/holders   # must be empty (kpartx/LVM holders?)
sudo multipath -f $WWID                             # "map in use" => something still open; do NOT force
for d in $PATHS; do sudo blockdev --flushbufs /dev/$d; echo 1 | sudo tee /sys/block/$d/device/delete; done
sudo multipath -w $WWID 2>/dev/null                 # drop it from /etc/multipath/wwids
sudo multipath -ll | grep -c $WWID                  # expect 0

# Array (only after every host is clean):
purehost disconnect --vol <prod-vol>-r1 <host>      # or: purehgroup disconnect --vol <prod-vol>-r1 <hgroup>
purevol destroy <prod-vol>-r1                       # 24h destroyed bucket; purevol eradicate to purge (SafeMode may block)
purevol destroy <prod-vol>.restore1                 # snapshot, if no longer needed
```

Pitfalls:
- If you **disconnect on the array first**, Ubuntu 22.04/24.04 leave failed paths and maps behind. Automatic purge needs multipath-tools 0.14 with `purge_disconnected "yes"`, which arrives with Ubuntu 26.04. Pure warns that a stale device becomes dangerous "if a different device is later connected using the same LUN number" [DOC [Everpure Target-Driven Rescan](https://support.everpuredata.com/r/linux/target-driven-rescan-on-linux)].
- With `no_path_retry 0`, a still-mounted clone whose volume was pulled raises I/O errors, which **withdraws GFS2** for that mount. Recovery is umount, then reboot if needed [DOC 9.1 Troubleshooting].
- `multipath -F` flushes **all** unused maps. Use `-f <wwid>` instead.
- Never `echo 1 > .../delete` while the dm map still exists. Paths whose map is still open queue or fail I/O for anything holding the map.
- `dmsetup remove --force` replaces the table with an error target. Use it only as a last resort when the map is truly orphaned.

### A4. Crash consistency and quiescing

- **Volume-level safety.** A FlashArray snapshot is atomic per volume, and across a protection group for multi-LUN datastores. It captures every acknowledged write, because FlashArray has no volatile write cache. A snapshot is therefore equivalent to a power loss of every host at once. GFS2 metadata is journaled and replayed on first mount (A2.3), and file data uses `data=ordered` by default [DOC gfs2(5)]. **The snapshot is crash-consistent, which is the same guarantee as a host crash** [INF].
- **qcow2 consistency** depends on qemu honouring guest flushes. The `cache=none`, `directsync` and `writeback` modes all honour flushes. **`cache=unsafe` does not**, and data the guest believes is durable may be missing from the snapshot. Morpheus' default cache mode is undocumented. Check with `virsh dumpxml <dom> | grep "driver name='qemu'"`. With lazy_refcounts off (the default), crash leftovers are only leaked clusters (`qemu-img check -r leaks`) [INF].
- **Guest quiesce (application-consistent):** `virsh domfsfreeze <dom>` → array snapshot (pgroup) → `virsh domfsthaw <dom>`. This needs qemu-guest-agent and the `org.qemu.guest_agent.0` channel. Morpheus itself does a sync + `fsfreeze` (VSS on Windows) and uses `--quiesce` for its own file snapshots when the agent is connected [DOC 9.1 Snapshots]. Keep the freeze window short: freeze all VMs on the datastore across all hosts in parallel, snapshot, then thaw. Windows VSS aborts long freezes (about a 10 s commit window) [INF].
- **Do NOT freeze the host GFS2 filesystem** (`fsfreeze -f /mnt/<uuid>` or `dmsetup suspend`), even though Red Hat documents `dmsetup suspend` for hardware snapshots [DOC RHEL 8 §3.4].
  - A GFS2 freeze is cluster-wide (freeze glock), so it stalls every VM.
  - It also stalls Morpheus heartbeat writes. After 6 missed writes (about 2 minutes), APD protection shuts down non-pinned VMs [DOC 9.1 Architecture; INF on interaction].
- **Recommendation:** use crash-consistent pgroup snapshots for the datastore, plus `domfsfreeze` only for VMs that need application consistency (databases).

---

## Part B — Linux host best practices for FlashArray iSCSI (Ubuntu VME hosts)

### B1. Key sources and what they say

**Everpure Linux Recommended Settings** ([index](https://support.everpuredata.com/r/linux/linux-recommended-settings), revised 2026-02-13; children revised 2026-02 to 2026-09):

The sample `multipath.conf` ([Recommended DM-Multipath Settings](https://support.everpuredata.com/r/linux/recommended-dmmultipath-settings), rev. 2026-09-25):
```
defaults { polling_interval 10 }
devices {
  device {
    vendor "PURE"  product "FlashArray"
    path_selector "service-time 0"   hardware_handler "1 alua"
    path_grouping_policy group_by_prio   prio alua   failback immediate
    path_checker tur   fast_io_fail_tmo 10   user_friendly_names no
    no_path_retry 0   features 0   dev_loss_tmo 600
  }
}
```

The page also says the following (quoted or condensed):
- "Path prioritizers (ALUA...) and `failback immediate` must be enabled on hosts connected to arrays configured in an ActiveCluster."
- "fast_io_fail_tmo and dev_loss_tmo do not apply to iSCSI." The Debian guide contradicts this for `fast_io_fail_tmo`; see below.
- "add `find_multipaths yes`" if maps don't appear after a rescan.
- **Warning:** customers on Purity older than 6.5.5 / 6.6.4 "must add the statement `detect_prio "no"`", otherwise "half of the paths remaining in the ALUA state of Active/Non-optimized".

udev rules ([Queue Settings](https://support.everpuredata.com/r/linux/queue-settings), rev. 2026-08-14; Ubuntu path `/lib/udev/rules.d/99-pure-storage.rules`, RHEL `/etc/udev/rules.d/`):
```
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/scheduler}="none"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/scheduler}="none"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/add_random}="0"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/add_random}="0"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{queue/rq_affinity}="2"
ACTION=="add|change", KERNEL=="dm-[0-9]*", SUBSYSTEM=="block", ENV{DM_NAME}=="3624a937*", OPTIONS="nowatch", ATTR{queue/rq_affinity}="2"
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", OPTIONS="nowatch", ATTR{device/timeout}="60"
# Maximum IO Size page:
ACTION=="add|change", KERNEL=="sd*[!0-9]", SUBSYSTEM=="block", ENV{ID_VENDOR}=="PURE", ATTR{queue/max_sectors_kb}="4096"
```

Other Recommended Settings content:
- `max_sectors_kb` must be ≤ 4096: "The Everpure FlashArray can handle a maximum of 4MB writes".
- The FlashArray may "take up to 60 seconds to service an individual IO" in worst-case recoverable errors, which is why the SCSI timeout is 60.
- Space Reclamation only says to "make sure that space reclamation is configured". The detail lives in a separate article that I could not retrieve.
- ActiveCluster: "Additional multipath settings are required", which points to "Linux Host Configuration Requirements" (not retrieved).

**Everpure iSCSI on Debian/Ubuntu Best Practices** ([Multipath](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-multipath-configuration) rev. 2026-10-15, [Performance Tuning](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-performance-tuning), [HA](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-high-availability), [Network Concepts](https://support.everpuredata.com/r/linux/network-concepts)). These pages carry a "reference only" disclaimer, and their measured findings are the most useful part:
- **`node.session.timeo.replacement_timeout` has no effect on a multipathed volume.** multipathd overwrites the session `recovery_tmo` with `fast_io_fail_tmo`. The stated trap: "the same iscsid.conf yields ~20 s on a multipathed volume and ~130 s on a single-path one."
- Tolerance = `(noop_out_interval + noop_out_timeout + recovery_tmo) + (no_path_retry × polling_interval)`. With 5/5, 10 and polling 10, the measured rides are: `no_path_retry 0` gives about 20.5 s, 3 gives about 50 s, 5 about 70 s, 10 about 120 s, and `queue` is unbounded.
- "Verify the effective value, never the config file". Use `multipathd show maps status` (the `queueing` column) and `multipath -t`. Files in `/etc/multipath/conf.d/` layer after `multipath.conf`, and the last definition wins.
- Queue depth: "Multipathed flash (2-4 paths): queue_depth 32, cmds_max 128". These equal the open-iscsi defaults.
- "iscsid.conf applies to new node records only". Existing records in `/var/lib/iscsi/nodes/` need `iscsiadm -m node ... -o update` followed by re-login.
- Same-subnet multi-NIC needs `arp_ignore = 2` and `arp_announce = 2` (all, default and per interface). This is "not needed when using different subnets".
- The Security page suggests `rp_filter = 1` on storage interfaces. That conflicts with a same-subnet multi-NIC layout. Use 2 (loose) or 0 there [INF].
- Pure's TCP buffer, `vm.dirty_*` and `nr_requests 256` suggestions are generic. Treat them as optional; they are not compliance items.

**Everpure HPE VME guides.** iSCSI pages revised 2026-05-26/27, FC pages 2026-07-15.
- [iSCSI Quick Start](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-quickstart):
  - Per-fabric iface binding: `iscsiadm -m iface -I <nic> --op=new` / `--op=update -n iface.net_ifacename -v <nic>`.
  - Discover each fabric's two controller portals through that fabric's iface only, and delete nodes on unwanted subnets.
  - Then `iscsiadm -m node -l`, `-o update -n node.startup -v automatic`, and expect **4 sessions per host**.
  - Verify MTU 9000 with `ping -c 3 -M do -s 8972 <ct0/ct1 fabric A/B portal>`.
  - Step 8 is `iscsiadm -m session --rescan` on every host before the LUN appears in the dropdown.
- [iSCSI Multipath](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-multipath-configuration): `defaults { user_friendly_names yes; find_multipaths no; enable_foreign "^$" }`, a blacklist of `devnode "^(ram|raw|loop|fd|md|dm-|sr|scd|st|nvme)[0-9]*"`, `"^hd[a-z]"` and `"^cciss.*"`, and a **commented-out** devices template (dev_loss_tmo 60, no_path_retry 0, rr_min_io_rq 1).
- [Network](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-network-configuration): MTU 9000 on hosts, switches and array, separate NICs per fabric, dedicated VLANs. [Architecture](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-architecture-overview): minimum 3 nodes for GFS2 quorum, and multipath is required.
- [Monitoring](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-monitoring-verification): `iscsiadm -m session` (4) and `multipath -ll` (all active).
- [FC Multipath](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-multipath-configuration) (the only VME page with a real PURE stanza):
  - The stanza: `user_friendly_names no`, `find_multipaths no`, `polling_interval 10`, PURE/FlashArray with service-time 0, group_by_prio, alua, "1 alua", immediate, tur, fast_io_fail_tmo 10, dev_loss_tmo 60, no_path_retry 0.
  - "must be identical on every node".
  - `user_friendly_names no` because the shared datastore needs the same `/dev/mapper/<wwid>` everywhere.
- [FC Host Tuning](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-host-tuning): `/etc/udev/rules.d/99-pure-storage.rules` sets scheduler none, add_random 0, rq_affinity 2 and timeout 60 (sd only). Its rules match partitions (`KERNEL=="sd*"`) and have no dm rules, so the Linux KB rules are the better set.
- [FC HA](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-high-availability): rolling reboots one node at a time, and a path-failover test with `echo offline > /sys/block/sdX/device/state`.

**Conflicts to resolve in the checker:**
1. `user_friendly_names`: the iSCSI VME page says yes, while the FC VME page, the Linux KB and Morpheus' WWN requirement say no. **Use `no`.**
2. `dev_loss_tmo`: 60 on the VME pages, 600 in the KB. It is not applicable to iSCSI, so make it informational.
3. Expected `multipath -ll` grouping: the iSCSI VME sample shows prio 50 and prio 10 groups, but the FC Quick Reference says "single prio=50 group". On a single non-ActiveCluster FlashArray, all paths are Active/Optimized, so **expect one prio=50 group**. Two groups means ActiveCluster preferred-array, or a prio-detection problem (the detect_prio warning).
4. The HVM documentation says VME "handles... enabling multipathing", and `sudo hvmcli storage multipath validate|status` exists on layout 2.0 [DOC 9.1 Troubleshooting]. **The remediator must not fight Morpheus.** Prefer a drop-in `/etc/multipath/conf.d/pure.conf`, and diff against `hvmcli` output.

**multipath-tools built-in hwtable for PURE** [SRC [opensvc/multipath-tools hwtable.c](https://github.com/opensvc/multipath-tools/blob/master/libmultipath/hwtable.c)]:
- 0.8.8 and 0.9.4 contain `pgpolicy GROUP_BY_PRIO, pgfailback immediate, hwhandler "1 alua", prio alua, fast_io_fail 10, max_sectors_kb 4096`.
- `detect_prio = OFF` was added after 0.9.4 (it is present in 0.9.9).
- 0.11.1 drops `hwhandler` from the stanza (the kernel auto-attaches `scsi_dh_alua`).
- Ubuntu 22.04 ships 0.8.8 and 24.04 ships 0.9.4 (verify with `dpkg -l multipath-tools`). **The built-in is adequate for ALUA grouping and failback.** It lacks an explicit `no_path_retry` (the global default is fail/undef), `user_friendly_names no` (Ubuntu's packaged `/etc/multipath.conf` sets `yes`) and `detect_prio no`. Pin the explicit stanza anyway.

**UNMAP / discard:**
- Morpheus mounts GFS2 with `discard` (pre-1.3 documented), so file deletes and qcow2 hole-punches reach the array.
- In-guest reclaim also needs libvirt `<driver ... discard='unmap'/>` on the disk, a virtio-scsi or virtio-blk bus that supports discard, and `fstrim` or `discard` inside the guest [INF].
- `fstrim` on a GFS2 mount is the batch alternative if online discard ever shows latency [INF].
- Pure's current Linux reclaim article was not retrievable, and the older Pure page recommends the `discard` mount option ([Mount Volume and Provision Filesystem](https://support.everpuredata.com/r/linux/mount-volume-and-provision-filesystem)).

### B2. Compliance / remediation table

Severity: **H** risks an outage or corruption, **M** degrades HA or performance, **L** is hygiene or informational.

| ID | Check (file / command) | Expected | Remediation | Sev | Source |
|---|---|---|---|---|---|
| NET-01 | `ip -br link` / `ip link show <stor-if>` | `mtu 9000` on every storage interface (and its VLAN parent) | netplan `mtu: 9000`, then `netplan apply`; hvmcli-built hosts: `hvmcli virtswitch edit --virtswitch-name <vs> --mtu 9000 --force` (guidance only, never auto-remediated) | H | VME iSCSI QS, Network; Debian Network |
| NET-02 | `ping -I <if> -c3 -M do -s 8972 <each portal>` | 0% loss to every same-fabric portal | Fix switch / array MTU end to end | H | VME iSCSI QS step 1 |
| NET-03 | `ip route` | No default gateway on storage interfaces; one subnet per fabric | Remove `routes:`/`gateway4` from the storage netplan | M | Debian iSCSI Architecture |
| NET-04 | `sysctl net.ipv4.conf.{all,default,<if>}.arp_ignore` / `arp_announce` | `2` / `2` **if two or more storage NICs share a subnet**; otherwise N/A | `/etc/sysctl.d/99-iscsi-arp.conf`, then `sysctl --system` | H (same-subnet) | Everpure Network Concepts |
| NET-05 | `sysctl net.ipv4.conf.{all,<if>}.rp_filter` (effective = max(all, if)) | Same-subnet multi-NIC: 0 or 2. Separate subnets: 1 or 2 | sysctl drop-in | M | Debian Security (1) vs Network Concepts [INF] |
| NET-06 | `ethtool -a <if>` | Flow control rx/tx on (if the switch supports it) | `ethtool -A <if> rx on tx on` (persist via networkd-dispatcher / udev) | L | Debian Architecture, Network Concepts |
| NET-07 | Host firewall on storage interfaces (`ufw status`, `nft list ruleset`) | No filtering, or TCP/3260 allowed | `ufw allow in on <if>` | M | Debian Firewall |
| ISC-01 | `cat /etc/iscsi/initiatorname.iscsi` vs array host object | Unique IQN, registered on the FlashArray host | Register the IQN in Purity | H | VME iSCSI QS step 3 |
| ISC-02 | `iscsiadm -m iface` | One iface per storage NIC with `iface.net_ifacename` set | `iscsiadm -m iface -I <nic> --op=new` + `--op=update -n iface.net_ifacename -v <nic>` | H | VME iSCSI QS step 4 |
| ISC-03 | `iscsiadm -m session -P1` | Sessions = (#ifaces × same-fabric portals), 4 for the VME reference; each iface only to its own fabric's portals | Delete wrong-subnet nodes `iscsiadm -m node -o delete -p <ip>:3260`, then re-login | H | VME iSCSI QS steps 5–6, Monitoring |
| ISC-04 | `iscsiadm -m node -o show \| grep node.startup` and `/etc/iscsi/iscsid.conf` | `automatic` for every node record (Ubuntu's file default is `manual`) | `iscsiadm -m node -o update -n node.startup -v automatic`; set it in iscsid.conf for future discoveries | H | VME iSCSI QS step 6 |
| ISC-05 | `systemctl is-enabled iscsid.socket open-iscsi` | enabled | `systemctl enable --now iscsid open-iscsi` | H | Debian Considerations |
| ISC-06 | `iscsiadm -m node -o show \| grep -E 'noop_out_(interval\|timeout)'` | 5 / 5 (defaults) | `-o update -n node.conn[0].timeo.noop_out_interval -v 5` (and timeout) | M | Debian Multipath (tolerance formula) |
| ISC-07 | `node.session.timeo.replacement_timeout` | Informational only: overwritten by multipathd on multipathed LUNs. Flag single-path PURE LUNs instead | n/a | L | Debian Multipath |
| ISC-08 | `iscsiadm -m session -P2`; `cat /sys/block/sdX/device/queue_depth` | queue_depth 32, cmds_max 128 (2–4 paths) | `-o update -n node.session.queue_depth -v 32`, then re-login | L | Debian Performance |
| ISC-09 | CHAP (`node.session.auth.*`) | Per policy. VME has no UI for CHAP | iscsiadm `-o update`; never print the secrets | L | HPE Adding GFS2 Pools; Debian Security |
| MP-01 | `dpkg -l multipath-tools`; `systemctl is-active multipathd` | Installed and active | `apt install multipath-tools`; `systemctl enable --now multipathd` | H | Debian Considerations |
| MP-02 | `multipath -t` (effective) PURE stanza | path_grouping_policy group_by_prio, prio alua, failback immediate, path_checker tur, path_selector "service-time 0", hardware_handler "1 alua" (shown as hwhandler) | Drop-in `/etc/multipath/conf.d/pure.conf` with the KB stanza; `systemctl reload multipathd` | H | Linux KB DM-Multipath; VME FC Multipath |
| MP-03 | `multipathd show maps status` (queueing column) | `off` (no_path_retry 0 / fail), per HPE+Pure. Never `on` (queue) | `no_path_retry 0` in the PURE stanza | H | Debian Multipath; VME FC Multipath |
| MP-04 | `multipath -t \| grep fast_io_fail_tmo` (PURE) | 10 (this also sets iSCSI recovery_tmo) | stanza | M | Linux KB; Debian Multipath |
| MP-05 | `user_friendly_names` effective; `ls /dev/mapper` | `no`, maps named `3624a9370…` identically on every host | stanza / defaults `user_friendly_names no`; `multipath -r` (only when no GFS2 is mounted on the alias) | H | VME FC Multipath; Morpheus Storage Lifecycle |
| MP-06 | `find_multipaths` effective + blacklist | `no` (claim all PURE paths), **with the local boot disk blacklisted by WWID** (not `^sda$`) | `blacklist { wwid <boot-wwid> }` or `find_multipaths strict` with `/etc/multipath/wwids` | M | VME iSCSI and FC Multipath; Linux KB |
| MP-07 | `multipath -t \| grep detect_prio` (PURE) | `no` when multipath-tools < 0.9.6 (Ubuntu 22.04 0.8.8 / 24.04 0.9.4) unless Purity ≥ 6.5.5 / 6.6.4 | Add `detect_prio no` to the stanza | M | Linux KB warning; hwtable.c |
| MP-08 | `multipath -ll <wwid>` | Non-ActiveCluster: **one** path group `prio=50 status=active`, all paths `active ready running`, `hwhandler='1 alua'`, `policy='service-time 0'`, path count = sessions | Investigate prio (MP-07) or missing sessions (ISC-03) | H | Linux KB; VME FC Quick Ref |
| MP-09 | `multipath -t \| grep -E 'polling_interval'` | 10 (Pure). Restate the tolerance if changed | defaults `polling_interval 10` | L | Linux KB; Debian Multipath |
| MP-10 | `md5sum /etc/multipath.conf /etc/multipath/conf.d/*` across hosts | Identical on all cluster nodes | Config management | M | VME FC Multipath |
| MP-11 | `multipath -ll` vs `/sys/class/scsi_device/*/device/state` | No failed or faulty paths, no orphan PURE sd devices without a map | Cleanup per A3(iii) | M | Debian Monitoring; TDR KB |
| MP-12 | ActiveCluster only: prio groups 50/10 and `dev_loss_tmo` | Two groups (preferred array prio 50) | Set host preferred array; follow the AC host requirements | M | Linux KB DM-Multipath, ActiveCluster |
| MP-13 | `purge_disconnected` | Leave unset on Ubuntu 22.04/24.04 (needs multipath-tools 0.14, Ubuntu 26.04). With AC, only on Purity ≥ 6.9.6 | n/a | L | Everpure TDR KB |
| UDV-01 | `cat /sys/block/<pure sd and dm>/queue/scheduler` | `[none]` | `99-pure-storage.rules` (Linux KB set), then `udevadm control --reload-rules && udevadm trigger --type=devices --action=change` | M | Linux KB Queue Settings |
| UDV-02 | `.../queue/add_random` | `0` | same rules | L | Linux KB |
| UDV-03 | `.../queue/rq_affinity` | `2` | same rules | L | Linux KB |
| UDV-04 | `/sys/block/sdX/device/timeout` (PURE) | `60` | same rules | M | Linux KB; VME FC Host Tuning; Debian Multipath |
| UDV-05 | `.../queue/max_sectors_kb` (sd and dm) | ≤ 4096 (the hwtable also sets 4096) | rule `ATTR{queue/max_sectors_kb}="4096"` | M | Linux KB Max IO Size; hwtable.c |
| UDV-06 | Rules file location | One authoritative file (prefer `/etc/udev/rules.d/99-pure-storage.rules`; Pure documents `/lib/udev/...` for Ubuntu). No conflicting VME FC-style `KERNEL=="sd*"` duplicates | Consolidate | L | Linux KB; VME FC Host Tuning |
| KRN-01 | `uname -r` on 22.04 | HWE kernel (GFS2 datastore requirement) | `apt install linux-generic-hwe-22.04` (rolling, one node at a time) | H | Everpure FC VME-Specific |
| KRN-02 | 24.04 kernel 6.8 + stretch cluster: `journalctl -k \| grep -E 'validate_lock_args -22\|lm_lock ret -22'` | Absent | HVM OS 26.04 / kernel ≥ 6.13 when validated | M | Morpheus 9.1 Troubleshooting |
| GFS-01 | `grep gfs2 /proc/mounts` on every host | Every Morpheus datastore in `/opt/morpheus-node/.mounts` is mounted, device `/dev/mapper/3624a9370…` | Morpheus UI / HPE Support (no improvised remount cluster-wide) | H | Morpheus 9.1 Storage Lifecycle and Troubleshooting |
| GFS-02 | `for d in /sys/fs/gfs2/*; do cat $d/withdraw; done` | `0` or absent | Per host: umount/remount, else reboot (maintenance mode) | H | HPE manual-creation page; 9.1 Troubleshooting |
| GFS-03 | `tunegfs2 -l <dev> \| grep table` for all GFS2 LUNs visible to the cluster | Unique fsname per lock table cluster-wide (detects an un-resignatured clone) | Do not mount; resignature per A3(ii) | H | RHEL 8 GFS2; gfs2(5) |
| GFS-04 | `/proc/mounts` options | Includes `noatime`; `discard` if online reclaim is intended | Morpheus-managed; informational | L | HPE manual-creation page |
| GFS-05 | `dlm_tool ls`; `dlm_tool status -v`; `corosync-quorumtool -l` | No lockspace waiting on fencing; all members listed | HPE Support (no manual fence_ack) | H | 9.1 Troubleshooting |
| GFS-06 | Journals ≥ hosts: `gfs2_edit -p jindex <dev>` or the journal count from `tunegfs2 -l` | ≥ host count (32 default) | `gfs2_jadd -j N <mountpoint>` on one node | M | RHEL 8 GFS2 §3.6; Storage Lifecycle |
| VIRT-01 | `virsh dumpxml <dom> \| grep "driver name='qemu'"` | `cache` ≠ `unsafe`; `discard='unmap'` if guest reclaim is wanted | Morpheus disk / plan settings | M | [INF] |
| VIRT-02 | `virsh qemu-agent-command <dom> '{"execute":"guest-ping"}'` | Agent responds (required for quiesced snapshots) | Install qemu-guest-agent in the guest | L | Morpheus 9.1 Snapshots |
| PKG-01 | `dpkg -l sg3-utils open-iscsi multipath-tools gfs2-utils` | Installed (sg3-utils is needed for `sg_persist`, rescans) | `apt install` | L | Debian Considerations |

### B3. Notes for building the checker or remediator

- **Read effective state, not files.** Use `multipath -t`, `multipathd show maps status`, `iscsiadm -m node -o show` (per record), and sysfs. Pure's own measurements show the config files mislead.
- **Remediate gently:**
  - Use `systemctl reload multipathd` or `multipathd reconfigure`.
  - Never `multipath -F` on a host with mounted GFS2.
  - Never restart `corosync` or `dlm`. HPE says that "may trigger fencing" [DOC 9.1 Troubleshooting].
  - `udevadm trigger` re-applies rules to live devices. On RHEL 6/7 Pure warns it "may crash the host"; on Ubuntu it is generally safe, but run it in a maintenance window anyway [DOC Linux KB].
  - iSCSI record changes need a logout/login per path. Do them one path at a time, and check that `multipath -ll` is healthy between paths.
- **Coordinate with Morpheus.** Layout 2.0 hosts have `hvmcli storage multipath validate` / `status` and `hvmcli storage iscsi --multipath`. Run them first and treat their results as authoritative for what Morpheus expects.
- **Secrets.** CHAP secrets live in node records. The checker must mask `node.session.auth.password*` in any output.

---

## Sources

- Morpheus 9.1 HVM docs: [Architecture](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/architecture.html), [Storage Lifecycle](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/storage_operations.html), [Troubleshooting](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/troubleshooting.html), [Snapshots](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/hvm/snapshots.html), [Legacy HVM](https://docs.morpheusdata.com/en/9.1/infrastructure/clusters/mvm.html)
- HPE VME docs (support.hpe.com, JavaScript-rendered; read via headless Edge): [Creating a GFS2 Datastore Manually (9.0.2)](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008401en_us&page=GUID-8D4F622F-4A39-48BC-B600-06141C1E88E5.html&docLocale=en_US), [Adding GFS2 Pools](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008058en_us&page=GUID-3702D75B-9505-40D0-B220-7739F5F72760.html), [GFS2 Pools Overview](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008058en_us&page=GUID-880FEFB6-37C2-4FEB-B1B7-A23FAC1B3E27.html), [Maintaining GFS2 Consistency and HA](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008058en_us&page=GUID-B3241978-4E00-4630-A8AB-E597193E51E2.html), [Setting Heartbeat Datastores](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008401en_us&page=GUID-64001B38-0509-46D5-854E-3A8500798DEE.html)
- Everpure HPE VME series (read via the Fluid Topics content API `/api/khub/maps/OxIiyMWu3~L6x~l9qvtV_Q/topics/...`): [iSCSI Quick Start](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-quickstart), [Architecture](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-architecture-overview), [Concepts](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-hpe-vme-iscsi-concepts), [Network](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-network-configuration?contentId=qid3igTNTGtU9aSGs8TNGQ), [Initiator](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-iscsi-initiator-configuration?contentId=bo2FNgXyf33VFo7Zo2vd1w), [Multipath](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-multipath-configuration?contentId=Mg_5bNNvLvWErw3W7m4Lrg), [GFS2](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-gfs2-clustered-filesystem?contentId=u927rjFgTlPqYnYCBjJ7pw), [Monitoring](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-iscsi-best-practices-monitoring-verification?contentId=mnnhQv5bl0iFl87XSCIJ0g), [FC VME-Specific](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-hpe-vme-specific-considerations), [FC Multipath](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-multipath-configuration), [FC Host Tuning](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-host-tuning), [FC HA](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-high-availability), [FC Quick Reference](https://support.everpuredata.com/r/hewlett-packard-enterprise/hpe-vme-fc-best-practices-quick-reference)
- Everpure Linux KB: [Linux Recommended Settings](https://support.everpuredata.com/r/linux/linux-recommended-settings) and children ([Queue Settings](https://support.everpuredata.com/r/linux/queue-settings), [Max IO Size](https://support.everpuredata.com/r/linux/maximum-io-size-settings), [DM-Multipath](https://support.everpuredata.com/r/linux/recommended-dmmultipath-settings), [ActiveCluster](https://support.everpuredata.com/r/linux/activecluster-03), [SCSI Unit Attentions](https://support.everpuredata.com/r/linux/scsi-unit-attentions)), [Target-Driven Rescan](https://support.everpuredata.com/r/linux/target-driven-rescan-on-linux), [Network Concepts](https://support.everpuredata.com/r/linux/network-concepts), Debian/Ubuntu iSCSI BP ([Multipath](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-multipath-configuration), [Performance](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-performance-tuning), [HA](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-high-availability), [Security](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-security), [Network](https://support.everpuredata.com/r/linux/debian-iscsi-best-practices-network-configuration))
- gfs2-utils man pages: [gfs2(5)](https://manpages.debian.org/testing/gfs2-utils/gfs2.5.en.html), [tunegfs2(8)](https://manpages.debian.org/testing/gfs2-utils/tunegfs2.8.en.html), [fsck.gfs2(8)](https://manpages.debian.org/testing/gfs2-utils/fsck.gfs2.8.en.html); [RHEL 8 Configuring GFS2 file systems ch. 3](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/8/html/configuring_gfs2_file_systems/assembly_creating-mounting-gfs2-configuring-gfs2-file-systems); [RH solution 4075151](https://access.redhat.com/solutions/4075151)
- Linux v6.8 source: [fs/gfs2/ops_fstype.c](https://github.com/torvalds/linux/blob/v6.8/fs/gfs2/ops_fstype.c), [recovery.c](https://github.com/torvalds/linux/blob/v6.8/fs/gfs2/recovery.c), [sys.c](https://github.com/torvalds/linux/blob/v6.8/fs/gfs2/sys.c), [lock_dlm.c](https://github.com/torvalds/linux/blob/v6.8/fs/gfs2/lock_dlm.c); multipath-tools [hwtable.c](https://github.com/opensvc/multipath-tools/blob/master/libmultipath/hwtable.c) at tags 0.8.8 / 0.9.4 / 0.9.9 / 0.11.1
- Third party: [builtbyfood/morpheus-unmanaged-vm-disk-migrate](https://github.com/builtbyfood/morpheus-unmanaged-vm-disk-migrate). HPE Community threads return HTTP 403 to automated fetches, so only search snippets were used, and nothing in this report depends on them.

**Gaps (not retrievable or not documented):**
- The GFS2 mount options and libvirt pool name used on 1.3/2.0 layouts.
- The Morpheus qcow2 file naming on GFS2.
- Whether the Morpheus "Add datastore" path adopts an existing, resignatured GFS2 fs.
- Pure's "Reclaiming Space on Linux" article and its ActiveCluster "Linux Host Configuration Requirements".

Each of these can be checked on a test host in minutes: `findmnt -t gfs2 -o TARGET,SOURCE,OPTIONS`, `virsh pool-list --all --details`, `virsh domblklist --details`.
