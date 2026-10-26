# Pure FlashArray for HVM (Morpheus plugin)

**Download:** the plugin jar (`pure-flasharray-morpheus-plugin-<version>-all.jar`) is attached to each release on the
[Releases page](https://github.com/nburnett-io/pure-flasharray-morpheus-plugin/releases/latest). Install steps are
under [Install](#install).

A Morpheus Enterprise 9.1 plugin that brings the parts of Pure's vSphere plugin that matter on HVM (KVM) clusters into the
Morpheus UI:

- **Host groups.** Builds the FlashArray host group for an HVM cluster from the hosts' iSCSI IQNs. It shows the plan
  first and keeps it in sync as nodes are added.
- **Host compliance.** Checks every host's effective iSCSI, multipath, udev, network and GFS2 state against Pure's Linux
  recommendations and the HVM requirements. It can also remediate. Remediation only adds drop-in config and missing
  sessions; it never logs out a path. MTU is never changed automatically: a failing MTU check shows the exact fix
  (the `hvmcli virtswitch edit` command, or which netplan file to edit) to run in a maintenance window. The check also
  runs by itself (default every 24 h) and raises a Morpheus alarm on each failing host, cleared once it passes.
- **GFS2 datastores.** Creates the LUN, maps it to the host group and verifies that every host sees the same
  `/dev/mapper/3624a9370<serial>`. It then hands the device to Morpheus's built-in GFS2 datastore type, which formats it
  and mounts it cluster-wide. It can also grow a datastore end to end (array resize, then every host rescans, then
  `gfs2_grow` once).
- **Snapshot recovery without touching production.** Clones a FlashArray snapshot to a new volume, mounts it read-only
  on one host under a private GFS2 lock table, and copies a VM's disks back as new files. It can also re-signature the
  clone and present it as a new datastore; that option is experimental and behind a setting.
- **Snapshot policies, read only.** Shows which protection groups cover each datastore (directly, or through the host
  group or a host), their schedule, retention, replication and retention lock, and the array's SafeMode. Policies are
  set on the array; the plugin never creates or changes them. Customers see one line on their Restore tab, e.g.
  "Restore points are taken every 6 hours and kept for up to 18 days."
- **Reclaim space.** Lists restore leftovers (`.pre-restore-*` disks, file-restore copies) on each GFS2 datastore and
  deletes the ones no VM uses. Other files and Morpheus disks no VM holds are shown, never deleted.

Read [OVERVIEW.md](OVERVIEW.md) for the design and the reasons behind it. Validate it in a test environment, against your own
Morpheus, hosts and array, before running it in production.

## Build

```powershell
.\gradlew.bat test shadowJar          # JDK 17+; output build\libs\pure-flasharray-morpheus-plugin-<ver>-all.jar
```

In a folder synced by a cloud-drive client, set `$env:PLUGIN_BUILD_DIR="$env:LOCALAPPDATA\builds\pure-flasharray-morpheus-plugin"`
first. The sync client locks files under `build\` and Gradle fails with "Failed to clean up stale outputs".

## Install

1. Download `pure-flasharray-morpheus-plugin-<version>-all.jar` from
   [Releases](https://github.com/nburnett-io/pure-flasharray-morpheus-plugin/releases) (or build it, above). In
   Morpheus, go to **Administration > Integrations > Plugins > Add** and upload it. To upgrade, upload the new jar over
   the old one.
2. Plugin settings: the defaults need nothing. GFS2 registration is `in-process`, which needs no token and works for
   clusters in any tenant. `rest` needs the appliance URL and a token from a user **in the tenant that owns the
   cluster**; a master-tenant token gets "Cluster not found" for a subtenant's cluster.

   **Array tag namespace** (default `org.purehvm`): the plugin recognises its datastores and recovery clones by the
   tags it writes on the array, in this namespace only. Keep the default on a new install. An install upgraded from a
   build that used another namespace must keep that one, or its existing datastores show as "created outside the
   plugin".

   Do not change plugin settings with `PUT /api/plugins/{id}`. It replaces the whole settings map, so it clears the
   masked token and any setting you leave out. Use the UI.
3. Give operators the role permissions (table below). For a **subtenant**, first raise them on the tenant's **base
   role** (Administration > Tenants > tenant > base role, e.g. "Tenant - Cloud"), then on the tenant's own roles. A
   tenant role cannot exceed its base role. Without a permission, the tab shows a permission message or hides the
   buttons, because Morpheus answers 404 for plugin routes the role may not use.

   | Role permission | Lets the user |
   |---|---|
   | **Pure FlashArray Operations: Read** | open the cluster tab and view: arrays, hosts, compliance, snapshot policies, reclaimable space, Retire plans |
   | **Pure FlashArray Operations: Full** | the above, plus snapshot recovery: take a snapshot, open a recovery session, restore disks and VMs, refresh capacity |
   | **Pure FlashArray Administration: Full** | change the storage: link/unlink arrays, host groups, add/mount/rescan hosts, remediate, create/grow/retire/adopt/share datastores, delete restore leftovers. Give it **with** Operations |
   | **Pure FlashArray VM Restore** | the instance Restore tab (customers, and operators who restore from there) |

   Engineers who should only restore get Operations Full (and VM Restore). Since 0.10.0, Operations Full no longer
   changes the storage: grant Administration to the people who did that before.
4. Add the array under **Infrastructure > Storage > Servers > Add > Pure FlashArray (HVM)**. There are three ways to
   authenticate:
   - **Recommended:** an `array_admin` username and password, plus a new name such as `morpheus-hvm` in
     **Create storage_admin service user**. The plugin creates that user with the storage_admin role, issues its
     token and discards the admin login.
   - The username and password of an existing `storage_admin` user. The plugin creates (or reuses) its token and
     stores the token in place of the password.
   - An existing API token, with Username left empty.

   Tokens above storage_admin are refused unless you tick **Allow a token above storage_admin**.
   Leave **Certificate SHA-256 fingerprint** empty: the array's self-signed certificate is pinned on first connection,
   and any later change is refused. Paste a fingerprint first if you want to verify it out of band; clear the field to
   re-trust after a planned certificate replacement.
5. On an HVM cluster, open the **Pure FlashArray** tab. Link the array, preview and apply the host group, then run the
   compliance check.

### Tenants: who can do what

The plugin is built for a multi-tier Morpheus: company (master) → environment tenant (owns the clusters) → customer
tenants (their VMs on the shared datastores).

| Tier | Who | Gets |
|---|---|---|
| Operator | master tenant, the tenant that owns the cluster, tenant ids in **Operator tenants** | the cluster tab; needs **Pure FlashArray Operations** (Read to view, Full for snapshot recovery) and **Administration** to change the storage |
| Customer | every other tenant | no cluster tab, all cluster routes refused (403); restore of its own VMs with **Pure FlashArray VM Restore** |

Customer roles should get **Pure FlashArray VM Restore** and never **Operations**. Even if a customer role is given
Operations, the plugin still refuses it, so a wrong role cannot expose shared storage.

Every change, and every refused customer call, is recorded in Morpheus's audit log as "Pure FlashArray: …".

### Customer self-service restore (instance **Restore** tab)

Users with **Pure FlashArray VM Restore** get a **Restore** tab on their own HVM VMs. Operators need that permission
too to use the tab: **Operations** alone does not open it. The tab requires a single-VM
instance with every disk on one FlashArray GFS2 datastore. Each array snapshot of that datastore shows as a
**restore point**: a date and time, and whether it was *scheduled* or *on demand*. Customers never see snapshot,
protection-group, volume, datastore, array, host or cluster names, in the list, in progress lines or in errors.
Restore-point ids are opaque, and unexpected failures show a generic message with a reference. The real detail (which
snapshot, which error) goes to the audit log and the appliance log under that reference.

**Restore...** on a point offers:

| Option | VM must be | What happens |
|---|---|---|
| Overwrite the whole VM | stopped | every disk goes back to the point; the replaced disks are kept as `*.pre-restore-<UTC>` until an operator removes them |
| Overwrite chosen disks only | stopped | the same, for the ticked disks; the others keep their current contents |
| File recovery | running | a copy of one disk, as of the point, is hot-attached to the running VM (next free `vdX`) |
| Add a copy as a new disk | stopped | Morpheus Reconfigure adds an empty disk, which then receives the copy; it stays as a normal Morpheus disk |
| Clone to a new VM | either | a new VM with the same plan, disks and network is provisioned, stopped and given the point's disks; the original is not touched. By default it stays **stopped** and **disconnected**: tick *Power on automatically* and *Connect to network automatically* to change that; connect later with **Connect network** on its own Restore tab |

**Which points are shown.** The plugin indexes each snapshot once: it mounts it read-only, lists its files, and keeps
the list in `.pure-hvm/index/` on the datastore. The tab then hides points that hold none of the VM's disks and marks
points that hold only some. Whole-VM overwrite and clone are disabled for those, and only the disks the point holds can
be picked. A point not indexed yet shows as "not checked yet"; points taken before the VM was created are hidden. The
recovery sweeper does the indexing, newest first.

**Clone identity.** A clone carries the original's identity: computer name, domain SID, static IPs and services. Keep
it disconnected while the original runs. A Morpheus Reconfigure of the clone may rewrite its network definition and
reconnect it (not yet tested).

File recovery details:
- **Live only.** The copy is attached to the running VM only and never written to the VM's definition. The VM can
  never boot from it, and it leaves the VM when the VM powers off.
- **Same disk identity as the original.** On Windows, set it **Online** in Disk Management. On Linux, mount it by device
  and read-only (`mount -o ro /dev/vdX1 /mnt/restore`); XFS needs `nouuid`, and LVM needs `vgimportclone` first.
- **Removing it.** The user picks how long the copy stays attached: 4 to 72 h, default 24 (plugin settings
  *File-recovery copy: default / maximum hours*). The recovery sweeper then detaches and deletes it, or as soon as it
  is no longer attached (the VM powered off). **Keep** on the tab resets the time to N hours from now, never beyond the
  maximum. **Remove** deletes it at once.
- **Morpheus lists it while attached.** Morpheus's sync imports the attached copy as a volume of the VM. The plugin
  deletes that record when the copy is removed, or on the next tab load once it is no longer attached. Do not
  delete such a disk through Reconfigure.

"Add a copy as a new disk" only writes into a disk that did not exist at the restore point and holds no data, so it
cannot overwrite anything. For a Linux **boot** disk, use File recovery: a permanent copy has the same filesystem UUIDs
and labels, and the guest may boot from it.

### Retiring a datastore

**Retire...** on a datastore row first shows a read-only plan: the datastore's disks, the files on it, and the target
datastore (Auto picks the one with the most free space that fits). When you run it, Retire:

1. hands over the HA heartbeat and VM NVRAM/TPM roles to the target
2. drains the datastore
3. moves every VM disk off it live, using Morpheus Reconfigure
4. checks that it is empty, then deletes it in Morpheus
5. removes the LUN from the hosts and destroys it on the array

The LUN is not eradicated, so it can be recovered from the destroyed bucket until the array eradicates it.

The VM moves run as **you**, through Morpheus Reconfigure, so they need your normal rights on those instances. The
datastore steps (drain, heartbeat hand-over, delete) run inside the plugin and need **Administration**. Morpheus
does not let a tenant user change a datastore through its API, and when one tries, the datastore loses its tenant
entry.

### Automatic compliance check and alarms

Every **Automatic compliance check (hours)** (plugin setting, default 24, `0` turns it off) the recovery sweeper runs
the read-only compliance check on every linked cluster. A host that fails gets one Morpheus alarm (**Operations >
Alarms**, on the host) that names the failing checks: *error* if a HIGH check fails, otherwise *warning*, and also
*warning* when the check could not run on the host. The alarm is replaced when the failures change and cleared once the
host passes. **Run check** / **Check** in the tab update the alarms of the hosts they check, so a fixed host loses its
alarm at once. Changes in a host's failures are also written to the audit log. The last result shows under the
compliance section. It is held in memory, so the check runs again two minutes after the plugin loads.

### Reclaiming space

**Scan datastores** under **Reclaim space** lists, per GFS2 datastore:
- **Restore leftovers**: `<disk>.pre-restore-<UTC>` (the disk an in-place restore replaced; deleting it removes the
  way back) and file-restore copies the sweeper has not removed yet. Ticked ones can be deleted with
  **Administration**. The delete runs as a cluster job. It checks every host's libvirt again first and skips any file
  a VM uses. It only deletes regular files whose names are restore leftovers.
- **Other files** that are not Morpheus disks, and **Morpheus disks no VM holds**: shown only (they may be ISOs or
  someone's data; Morpheus disks are deleted in Morpheus).

### Several arrays and clusters

A cluster can link to any number of arrays (**Link another array** in the tab), and an array can serve any number of
clusters. Each link has its own host group, which shows as its own column in **Hosts**.

- **Datastores:** when a cluster has more than one link, Create asks which array to use. Grow, snapshots and recovery
  always act on the array that holds the volume.
- **Compliance:** checks every host against the iSCSI portals of all linked arrays.
- **Shared host groups:** two clusters never share a host group on the same array. The plugin refuses this, because
  both clusters would mount the same GFS2 LUNs.
- **Unlinking:** **Unlink** only forgets the link; nothing changes on the array. It is refused while the cluster still
  has datastores on that array.

## Layout

| Path | What |
|---|---|
| `src/main/groovy/.../api` | FlashArray REST 2.x client (version negotiation, token login, paging, certificate pinning) |
| `src/main/groovy/.../planning` | Host-group planner and applier |
| `src/main/groovy/.../compliance` | Host probe parser, checks, remediation script builder |
| `src/main/groovy/.../ops` | Host scripts and orchestration: provision, grow, recovery, clone |
| `src/main/groovy/.../morpheus` | Morpheus wiring: storage provider, cluster tab, controller, GFS2 registrars, jobs |
| `src/main/resources/scripts/pure-host-probe.sh` | Read-only host probe |
| `docs/research/` | Research behind the design: Morpheus SDK, GFS2 and Linux best practice |
| `docs/CUSTOMER-TENANT-ONBOARDING.md` | Setting up a customer tenant on a shared HVM cluster |

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This is an independent project. It is not affiliated with, endorsed by or supported by Pure Storage or Hewlett Packard
Enterprise. Pure Storage, FlashArray and Purity are trademarks of Pure Storage, Inc.; HPE, Morpheus and HPE VM
Essentials are trademarks of Hewlett Packard Enterprise.
