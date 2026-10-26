# 02 - Morpheus plugin SDK for an HVM + Pure FlashArray (iSCSI/GFS2) storage integration

Target: HPE Morpheus Enterprise 9.1, HVM (a.k.a. MVM/VME, KVM/libvirt) clusters, iSCSI to Pure FlashArray, GFS2 shared datastores.
Researched 2026-10-20. All source references are to files downloaded into
`<scratch>\sdk\` (abbreviated `sdk\` below):

| Dir | What |
|---|---|
| `sdk\core-1.5.0\morpheus-plugin-core-rel-1.5.0\` | GitHub tag `rel-1.5.0` of HewlettPackard/morpheus-plugin-core (also `core-1.4.2`) |
| `sdk\jar-1.5.0\` (`jar-1.4.0`, `jar-1.4.2`) | `morpheus-plugin-api-<v>-sources.jar` from Maven Central - **the authoritative published API**; paths below like `core\providers\DatastoreTypeProvider.java` are relative to `sdk\jar-1.5.0\com\morpheusdata\` |
| `sdk\ext-morpheus-plugin-core\` | default branch `v1.5.x` HEAD (version `1.5.1-SNAPSHOT`) |
| `sdk\samples\`, `sdk\alletra-mp\`, `sdk\ext-*` | samples repo, Alletra MP prototype, other HPE/community plugins |
| `sdk\openapi\ssr-props.json` | Morpheus REST API 9.1.0 OpenAPI 3.1 spec (key `.oasDefinition`); `sdk\paths.tsv` = all 1,105 operations |

Key docs inside the core repo (read these first - they are far better than the website):
`morpheus-plugin-docs\src\docs\asciidoc\StorageDatastorePlugin.adoc` (1,559 lines, "Storage Datastore Plugin for HVM" - architecture, lifecycle, snapshots, Ceph worked example), `DatastoreTypeProviders.adoc`, `BackupPlugins.adoc`, `TabsPlugin.adoc`, `HTTPRouting.adoc`, `Credentials.adoc`, `SyncTasks.adoc`, `ReleaseNotes.adoc`. The same text is the (200 KB) page at https://developer.morpheusdata.com/docs.

---

## 0. Headline findings

1. **Use `morpheus-plugin-api` 1.5.0 with `Morpheus-Min-Appliance-Version: 9.1.0`.**
   - The Morpheus 9.1.0 release notes say "Plugin API 1.5.0" and "Plugins built against older API versions may need recompilation". The compatibility page gives 9.0.x = Plugin API 1.4.1. (Source: https://docs.morpheusdata.com/en/latest/release_notes/current.html and `.../compatibility.html`.)
   - The SDK's own release notes stop at "1.3.x requires 8.1.x". The generator's `9.1.x` template still pins 1.3.0.
   - HPE's own plugins agree:
     - xenserver and nutanix-prism ship `api 1.4.0` + `minVersion=9.0.0`.
     - SCVMM moved to `api 1.5.1-SNAPSHOT` + `minVersion=9.1.0` on 2026-10-13 (commit e65330d3).
     - The Exchange `morpheus-alletramp-plugin` 1.14.0 requires min 9.1.0.
2. **Build with JDK 25 / Groovy 4 for 9.x.**
   - SCVMM commit ff60ae84 "Upgrade build dependencies for morpheus-ui 9.0 compatibility" changed the build to:
     - `javaVersion=25`
     - `groovyVersion=4.0.30` (`org.apache.groovy`)
     - Gradle 9.4.1
     - `com.gradleup.shadow 9.4.1`
     - `org.codehaus.groovy` excluded from the plugin-api dependency
   - The Veeam plugin README (per the docs research) requires Java 25 + Plugin API 1.5.x.
   - Plugin-api 1.4.0 switched to `jakarta.servlet`.
   - HPE still ships Groovy-3/Java-11-compiled plugins for 9.0 (xenserver, nutanix), so older bytecode does load. The developer docs ("Java 11 / Gradle 7") are stale.
3. **`MorpheusGfs2DatastoreService` (release-note "added in 1.4.0") is NOT in any published jar.** It was added in PR #156 (commit 28a77f08d5) and dropped again inside the same PR before merge. What shipped is "create/update/remove moved to `MorpheusDatastoreTypeService`" - routing calls, not GFS2 primitives. A later `MorpheusGfs2FilesystemService` (PR #203, "@since 1.5.0", built for the Alletra MP GFS2 plugin) was **reverted** (PR #210, 2026-07-10) "as the corresponding morpheus-ui implementation needs rework". Today there is no plugin-API GFS2 helper. The release-note item `StorageVolumeProvisioningFacet` (1.4.2) is likewise absent from the jars.
4. **There is no public, working storage plugin for HVM.**
   - The Alletra MP repo is a 2024 stub on API 1.1.8-SNAPSHOT with no licence, and it no longer compiles against current signatures.
   - StorPool's repo is generator scaffolding.
   - HPE's real Alletra plugin is closed source. It ships inside VME as the "Storage Integration Pack" / Exchange package `morpheus-alletramp-plugin` (1.0.1 to 1.14.0); its repo `HPE-EMU/morpheus-storage-alletra-mp-plugin` returns 404.
   - Use the in-repo `StorageDatastorePlugin.adoc` and its Ceph walkthrough as the template.
   - There is no Pure, Dell, NetApp or Nimble plugin on Exchange or GitHub. Pure on VME is "not supported officially" by HPE, though Pure publishes its own VME iSCSI/GFS2 best-practice guides.
5. **HA failover does not call plugin hooks.** Every LUN must already be presented to every host in the cluster (adoc line 45). For Pure, that means one host group per cluster, with every volume connected to that host group.
6. **Morpheus 9.1 already does a lot host-side for GFS2.** You don't need a plugin to run iSCSI login or mkfs:
   - Cluster > Storage > iSCSI: add a target portal, and it auto-discovers and logs in on all hosts.
   - The new 9.1 "Host Storage Interfaces" view handles discovery, async rescan and multipath status.
   - Layout 2.0 adds `hvmcli storage ...` commands.
   - The built-in "HPE Clustered Datastore (Shared LUN)" type does mkfs and mount on top of that.

   The plugin's value is in what the UI cannot do:
   - array-side LUN, host and host-group lifecycle
   - per-NIC iface binding (missing from the UI, per Pure's guide)
   - Pure-specific multipath and udev settings, and compliance checks
   - LUN grow plus `gfs2_grow` (there is no grow action in 9.1)
   - array snapshots, clones and restores

---

## 1. Toolchain, build, install, settings and secrets

### 1.1 Versions

| Item | Value / evidence |
|---|---|
| Latest plugin-api | 1.5.0 (Maven Central `com.morpheusdata:morpheus-plugin-api`, tag `rel-1.5.0`, 2026-10-01). HEAD is 1.5.1-SNAPSHOT (adds `StorageProviderVolumeGroupsFacet`, replication facets, file-share defaults). |
| For 9.1 | **1.5.0** + `Morpheus-Min-Appliance-Version: 9.1.0` (9.1.0 release notes: "Plugin API 1.5.0"). 1.5.0 adds `ActionProvider`, `MULTI_TEXT`/`MULTI_KEY_VALUE` inputs, `updateBackup`, backup capability flags. |
| For 9.0 + 9.1 | 1.4.1/1.4.2 + `9.0.0` (compatibility page: 9.0.x = API 1.4.1; Alletra MP 1.14.x "Requires Plugin API 1.4.1+, minimum Manager 9.0.2"). 1.4.x adds jakarta servlet, `VolumeConfigValidationFacet`, `refresh`/`refreshDaily` for StorageServer, `addServerGroupServers`, `MorpheusPackageService`. A 1.5.0-compiled plugin may fail to load on 9.0 if it references 1.5-only classes. |
| JDK / Groovy / Gradle | JDK 25, Groovy 4.0.30, Gradle 9.4.1 + `com.gradleup.shadow` 9.4.1 (HPE SCVMM, Veeam README); Groovy 3.0.x/Java 11 bytecode still loads (HPE xenserver/nutanix for 9.0) |
| plugin-api build itself | `sourceCompatibility 1.11`, Groovy 3.0.9, rxjava3 3.1.7, handlebars 4.3.1, httpclient 4.5.14 (`morpheus-plugin-api\build.gradle`) |
| Gradle plugin | `com.morpheusdata:morpheus-plugin-gradle` 1.5.0 exists on Central (adds `scribePackage`, `scribeResources`, `i18nPackage` tasks; SCVMM uses 1.2.14) |
| Generator | https://developer.morpheusdata.com "Getting Started" generator has a 9.1.x option (`morpheus-plugin-site\src\main\resources\project-templates\9.1.x\groovy\`) - has a `datastoreType` and `storage` template, but pins api 1.3.0 / min appliance 6.3.0: bump both. |
| Karman | needed `compileOnly`/`provided` because `DatastoreTypeProvider.cloneVolume(..., CloudFileInterface)` references `com.bertramlabs.plugins.karman.CloudFileInterface` (template uses `karmanVersion=2.3.1`) |

### 1.2 build.gradle skeleton (9.1, following HPE's current SCVMM layout)

```groovy
// gradle.properties
// version=0.1.0
// javaVersion=25            // or 17/21 if you want bytecode that also loads on older JVMs; HPE's 9.0 xenserver/nutanix still use 11
// morpheusApiVersion=1.5.0
// morpheusGradleVersion=1.5.0
// minVersion=9.1.0
// groovyVersion=4.0.30
// karmanVersion=2.3.1
// assetPipelineVersion=4.4.0
// spockVersion=2.3-groovy-4.0

buildscript {
    repositories { mavenCentral(); maven { url "https://plugins.gradle.org/m2/" } }
    dependencies {
        classpath "com.morpheusdata:morpheus-plugin-gradle:$morpheusGradleVersion"
        classpath "com.bertramlabs.plugins:asset-pipeline-gradle:$assetPipelineVersion"
    }
}
plugins {
    id 'java'
    id 'groovy'
    id 'com.gradleup.shadow' version '9.4.1'      // older template: com.github.johnrengelman.shadow 8.1.1
}
apply plugin: 'asset-pipeline'
apply plugin: 'com.morpheusdata.morpheus-plugin-gradle'

group = 'org.purehvm'
java { sourceCompatibility = JavaVersion.toVersion(javaVersion); targetCompatibility = JavaVersion.toVersion(javaVersion) }
repositories { mavenCentral() }
configurations { provided }
dependencies {
    provided("com.morpheusdata:morpheus-plugin-api:$morpheusApiVersion") { exclude group: 'org.codehaus.groovy' }
    provided "org.apache.groovy:groovy-all:$groovyVersion"
    provided("com.bertramlabs.plugins:karman-core:$karmanVersion") { exclude module: 'groovy-all' }
    // anything you bundle (e.g. nothing: HttpApiClient in plugin-api is enough for Purity REST 2.x)
    testImplementation "org.spockframework:spock-core:$spockVersion"
}
sourceSets { main { compileClasspath += configurations.provided } }

jar {
    manifest {
        attributes(
            'Plugin-Class'                  : 'org.purehvm.PureHvmPlugin',
            'Plugin-Version'                : version,
            'Morpheus-Name'                 : 'Pure FlashArray for HVM',
            'Morpheus-Organization'         : 'Pure FlashArray for HVM contributors',
            'Morpheus-Code'                 : 'pure-hvm',            // must equal Plugin.getCode()
            'Morpheus-Description'          : 'FlashArray iSCSI/GFS2 integration for HVM clusters',
            'Morpheus-Logo'                 : 'assets/pure.svg',
            'Morpheus-Logo-Dark'            : 'assets/pure-dark.svg',
            'Morpheus-Labels'               : 'Plugin, Storage',
            'Morpheus-Repo'                 : 'https://github.com/<owner>/<repo>',
            'Morpheus-Min-Appliance-Version': minVersion
        )
    }
}
assets { packagePlugin = false }
tasks.assemble.dependsOn tasks.shadowJar        // output: build/libs/*-all.jar
```

Project layout (`GettingStarted.adoc`): `src/main/groovy`, `src/main/resources/renderer/hbs/` (handlebars views), `src/main/resources/i18n`, `src/main/resources/scribe` (seed data), `src/main/resources/packages`, `src/assets/{images,javascript,stylesheets}`. Each plugin is loaded in its own classloader (README).

### 1.3 Install / upgrade

- **UI:** Administration > Integrations > Plugins > Add, upload the shadow jar. To upgrade, upload the new jar over the old one. The plugin is replaced in place and handlebars/assets reload (1.1.6 note). Keep `Morpheus-Code` and the provider codes stable, because seeded `DatastoreType`/`StorageServerType` rows are keyed by code.
- **REST (9.1 spec):**
  - `POST /api/plugins/upload` takes multipart field `file`, with `plugin` as an alias. It installs or updates asynchronously, with status going `installing`/`updating` → `loaded`/`error`.
  - `GET /api/plugins`.
  - `PUT /api/plugins/{id}` with `{plugin:{enabled, config{}}}` sets settings.
  - New in 9.1: `PUT /api/plugins/{id}/revert`, `POST /api/plugins/{id}/upgrade`, `POST /api/plugins/check-updates`.
- **CI fit:** build the jar, attach it to a release or package registry, then `POST /api/plugins/upload` with a bearer token held in DPAPI/credential storage.

### 1.4 Settings and secrets (no hard-coding)

Prefer them in this order:

1. **Per-array credentials on the Storage Server form (idiomatic).** In `StorageServerType.optionTypes`, add a `CREDENTIAL` input plus `localCredential:true` username/password fields. This is the Alletra prototype and `Credentials.adoc` pattern:
   ```groovy
   new OptionType(code:'pure.credential', name:'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName:'type',
       fieldLabel:'Credentials', fieldContext:'credential', required:true, displayOrder:10, defaultValue:'local',
       optionSource:'credentials', config:'{"credentialTypes":["api-key","username-password"]}'),   // check type codes via GET /api/credential-types
   new OptionType(code:'pure.servicePassword', name:'API Token', inputType: OptionType.InputType.PASSWORD,
       fieldName:'servicePassword', fieldContext:'domain', localCredential:true, displayOrder:12)
   ```
   - **Read at runtime:** `storageServer.credentialData?.password ?: storageServer.servicePassword`. The `StorageServer` fields `credentialLoaded` and `credentialData` exist. You can also load explicitly with `morpheus.services.accountCredential.loadCredentials(StorageServer)`, which returns `AccountCredential` (`MorpheusSynchronousAccountCredentialService.java:48`).
   - **Storage:** Morpheus encrypts these. Credentials can live in Infrastructure > Trust > Credentials, optionally backed by an external store.
   - **Pure fit:** for Pure, a Purity API token mapped to `servicePassword` (or an api-key credential type) avoids storing any password.
2. **Plugin settings for global knobs** (timeouts, iSCSI interface names, multipath policy). Use `Plugin.setSettings(List<OptionType>)` / `getSettings()` (`core\Plugin.java:129-135`). Read them with `String json = morpheus.getSettings(plugin).blockingGet()` (`MorpheusContext.java:326`) and parse with `JsonSlurper`. A `PASSWORD` input here is masked in the UI, but treat it as less protected than credentials.
3. **Cypher**, for plugin-generated secrets such as CHAP secrets it creates per host. `morpheus.services.cypher` provides `read/write/delete(CypherAccess, key[, value, leaseTimeout])` and `readPassword` (`core\synchronous\cypher\MorpheusSynchronousCypherService.java`). A `CypherModuleProvider` is only needed to add a new backend.

Never put secrets in `Datastore.configMap` or `StorageVolume.configMap`, because those are plain JSON visible over the API.

---

## 2. StorageProvider: the FlashArray as a "Storage Server"

### 2.1 Interfaces

`core\providers\StorageProvider.java` (extends `PluginProvider, UIExtensionProvider`):
```java
String getDescription();
Icon getIcon();
StorageServerType getStorageServerType();
ServiceResponse verifyStorageServer(StorageServer storageServer, Map opts);      // validate URL/token; field errors keyed by fieldName
ServiceResponse initializeStorageServer(StorageServer storageServer, Map opts);  // first save; usually calls refresh
ServiceResponse refreshStorageServer(StorageServer storageServer, Map opts);     // periodic sync; success=false -> status 'error'
default HTMLResponse renderTemplate(StorageServer storageServer)                 // optional custom detail view
interface StorageUpdateFacet / StorageConfigurationDriftCheckFacet               // optional (firmware/update, drift)
```
- Extend `AbstractStorageProvider` (`core\providers\AbstractStorageProvider.java`). It wires a `HandlebarsRenderer` with asset, nonce and i18n helpers.
- `StorageProviderVolumes` (`core\providers\StorageProviderVolumes.java`) adds volume management on the Storage UI:
  - `createVolume`, `resizeVolume`, `updateVolume`, `deleteVolume`, each `(StorageGroup, StorageVolume, Map)`, plus default `(StorageServer, StorageVolume, Map)` overloads. `updateVolume` was added in 1.2.13.
  - `Collection<StorageVolumeType> getStorageVolumeTypes()`.
- `StorageProviderBuckets` and `StorageProviderFileShares` cover object storage and NFS/SMB. They are not needed here.
- In HEAD (1.5.1-SNAPSHOT, not yet released):
  - `StorageProviderVolumeGroupsFacet`: `createVolumeGroup`, `addVolumesToVolumeGroup` and related calls on a `StorageVolumeGroup`. Its javadoc example is an Alletra volume set, which maps naturally to Pure *protection groups*.
  - `StorageProviderReplicationGroupFacet`, `StorageReplicationProvider` (failover/failback/pause/resume) and `StorageProviderReplicationPartnerFacet`. Worth watching for ActiveDR/async replication.

### 2.2 StorageServerType flags

`model\StorageServerType.java`:
- **Fields:** `code, name, description, storageService, enabled, creatable, hasNamespaces, hasGroups, hasBlock, hasObject, hasFile, hasDatastore, hasDisks, hasHosts, createNamespaces, createGroup, createBlock, createObject, createFile, createDatastore, createDisk, createHost, iconCode, hasFileBrowser`, plus `optionTypes`, `volumeTypes`.
- **Pure flags:** `hasBlock:true, hasDatastore:true, hasDisks:true, createBlock:true, createDisk:true, hasHosts:true, hasGroups:true`. Hosts and groups expose the Pure hosts and host groups.
- **Code matching:** `StorageServerType.code` must equal `DatastoreTypeProvider.getStorageProviderCode()` (adoc line 225).

`model\StorageServer.java` gives you:
- `serviceUrl, serviceHost, servicePort, serviceUsername, servicePassword, serviceToken`
- `serialNumber, serverVendor, serverModel, serviceVersion`
- `maxStorage, usedStorage, status, statusMessage, rawData`
- `credentialData`, `groups` (StorageGroup = pools; use one "array" group or pods), `hostGroups`, `hosts`

### 2.3 Hosts and host groups (added 1.2.13)

Models:
- **`model\StorageHost.java`:** `storageServer, name, code, category, internalId, externalId, config, status, statusMessage, fiberPort, fiberNode, fiberSlot, fiberWwn, iscsiPath, initiatorChapName, targetChapName, initiatorChap, targetChap, hostType, hostPersona, accounts, hosts (Collection<ComputeServer>)`.
  - This maps 1:1 to a Purity host: name, IQNs, CHAP, personality.
  - `hosts` links the array host back to the HVM `ComputeServer`(s).
  - `iscsiPath` is a single string, so keep the full IQN list in `config` as well.
- **`model\StorageHostGroup.java`:** `storageServer, name, code, internalId, externalId, config, status, hosts (List<StorageHost>), volumes (List<StorageVolume>), accounts`. This maps to a Purity host group and its connected volumes.

Services:
- `morpheus.services.storageHost` → `MorpheusSynchronousStorageHostService`
- `morpheus.services.storageHostGroup` → `MorpheusSynchronousStorageHostGroupService`
- Async equivalents are under `morpheus.async.*` (`core\MorpheusServices.java:274,281`).
- These are standard data services: list, find, get, create, bulkCreate, save, bulkSave, remove, listIdentityProjections.
- **Not exposed in REST 9.1:** the spec has no storage-hosts or storage-groups endpoints, so this data is only visible through plugin UI or as storage-server sub-objects.

### 2.4 Sync pattern for refreshStorageServer

The pattern comes from `SyncTasks.adoc`, using `core\util\SyncTask.java`:
```groovy
def existing = morpheus.async.storageVolume.listIdentityProjections(new DataQuery().withFilter('storageServer.id', server.id))
// (use the storage.volume service on 1.2.5+: morpheus.async.storage.volume)
new SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume>(existing, pureVolumes)
    .addMatchFunction { StorageVolumeIdentityProjection d, Map api -> d.externalId == api.serial }   // Pure volume serial -> WWN = 3624a9370 + serial
    .withLoadObjectDetailsFromFinder { updates -> morpheus.async.storageVolume.listById(updates*.existingItem*.id) }
    .onAdd    { adds    -> morpheus.async.storageVolume.bulkCreate(adds.collect { buildVolume(it) }).blockingGet() }
    .onUpdate { updates -> /* compare size/used/name */ morpheus.async.storageVolume.bulkSave(changed).blockingGet() }
    .onDelete { removes -> morpheus.async.storageVolume.bulkRemove(removes).blockingGet() }
    .start()
```
Run one SyncTask each for Pure hosts → `StorageHost`, host groups → `StorageHostGroup`, volumes → `StorageVolume` (`wwn`, `externalId`, `maxStorage`, `usedStorage`), and optionally snapshots and protection groups. After that, set `StorageServer.maxStorage`/`usedStorage` and save.

1.4.2 added `refresh`/`refreshDaily` on the StorageServer service to trigger a refresh from code. REST has no storage-server refresh endpoint.

---

## 3. DatastoreTypeProvider for HVM: the two models

### 3.1 Interface (published 1.5.0, `core\providers\DatastoreTypeProvider.java`)

```java
String getProvisionTypeCode();            // 'kvm' for HVM (adoc line 267)
String getStorageProviderCode();          // == StorageServerType.code
List<OptionType> getOptionTypes();        // datastore-create dialog
default List<StorageVolumeType> getVolumeTypes()
default String getExternalType(); default String getExternalSubType();
default boolean getHeartbeatTargetCapable();   // HA heartbeat datastore
default boolean getImageTargetCapable();       // can hold the image cache
default boolean getCheckpointTargetCapable();  // VM checkpoint target (1.2.13)
boolean getCreatable(); boolean getEditable(); boolean getRemovable();
default boolean getLocalStorage();             // false = shared
default boolean getPluginManagedVolumeRefresh();
ServiceResponse removeVolume(StorageVolume volume, ComputeServer server, boolean removeSnapshots, boolean force);
ServiceResponse<StorageVolume> createVolume(StorageVolume volume, ComputeServer server);
ServiceResponse<StorageVolume> cloneVolume(StorageVolume volume, ComputeServer server, StorageVolume sourceVolume);
ServiceResponse<StorageVolume> cloneVolume(StorageVolume volume, ComputeServer server, VirtualImage virtualImage, CloudFileInterface cloudFile);
ServiceResponse<StorageVolume> resizeVolume(StorageVolume volume, ComputeServer server, Long newSize);
default ServiceResponse<StorageVolume> prepareVolumeAttach(StorageVolume volume, ComputeServer server);
default ServiceResponse validateDatastore(Datastore datastore);
ServiceResponse<Datastore> createDatastore(Datastore datastore);
default ServiceResponse<Datastore> updateDatastore(Datastore datastore);
default ServiceResponse validateRemoveDatastore(Datastore datastore);    // 1.3.3
ServiceResponse removeDatastore(Datastore datastore);
default ServiceResponse<Datastore> refreshDatastore(Datastore datastore);
default ServiceResponse<Collection<StorageVolumeType>> filterStorageVolumeTypes(DatastoreType, Collection<StorageVolumeType>);  // 1.3.1

interface SnapshotFacet { createSnapshot(StorageVolume); removeSnapshot(StorageVolume); listSnapshots(StorageServer); cloneVolume(StorageVolume, Snapshot);
  interface SnapshotServerFacet  { ServiceResponse<Snapshot> createSnapshot(ComputeServer server, Boolean forBackup, Boolean forExport);
                                   ServiceResponse<Snapshot> revertSnapshot(ComputeServer server, Snapshot snapshot);
                                   ServiceResponse removeSnapshot(ComputeServer server, Snapshot snapshot); }
  interface SnapshotInstanceFacet{ createSnapshot(Instance, CreateSnapshotRequest); revertSnapshot(Instance, Snapshot); removeSnapshot(Instance, Snapshot); } }
interface ComputeProvisionFacet { prepareServerForVolume(ComputeServer, StorageVolume); releaseVolumeFromServer(ComputeServer, StorageVolume); }  // 1.2.10, non-MVM (baremetal)
interface MvmProvisionFacet {
  ServiceResponse<StorageVolume> prepareHostForVolume(ComputeServerGroup cluster, ComputeServer server, StorageVolume volume);
  ServiceResponse<MvmDiskConfig> buildDiskConfig(ComputeServerGroup cluster, ComputeServer server, StorageVolume volume);
  ServiceResponse<StorageVolume> releaseVolumeFromHost(ComputeServerGroup cluster, ComputeServer server, StorageVolume volume);
  class MvmDiskConfig { DiskMode diskMode /*VIRTIO,VIRTIO_SCSI,SATA,IDE*/; String diskType /*'block'|'file'|'rbd'*/; String deviceName; DeviceType deviceType /*DISK,CDROM,FLOPPY*/; } }
interface DatastoreEventFacet extends GenericIntegrationProvider.EventSubscriberFacet<DatastoreEvent>   // default types SERVER_MOVE, SERVER_SHUTDOWN, SERVER_STARTUP
interface VolumeConfigValidationFacet { ServiceResponse validateVolumeConfig(Datastore, ValidateVolumeConfigRequest, Map opts); }  // 1.4.2; errors under key "volume" (e.g. Pure name rules / 1 MiB-aligned sizes)
```
Note that `server` in the MvmProvisionFacet calls is the **VM**. The hypervisor is `server.parentServer`, and `Datastore.refId` is the cluster (`ComputeServerGroup`) id (adoc lines 348, 528).

Older signatures (Alletra prototype, plugin-api 1.1.8) were `prepareHostForVolume(ComputeServerGroup, StorageVolume)` and `createSnapshot(ComputeServer)`. Those no longer exist.

### Routing / helper services
- **`morpheus.services.storage.datastoreType`** (`core\storage\MorpheusDatastoreTypeService.java`) routes `createVolume`, `removeVolume`, `cloneVolume`, `resizeVolume(volume, server, boolean isShutdown)`, `refreshDatastore`, the server and instance snapshot calls, and (since 1.4.0) `createDatastore`/`updateDatastore`/`removeDatastore` to whichever provider owns the datastore type. That provider can be built-in, such as GFS2 or NFS. The caller saves.
- **`morpheus.services.storage.vmeQcow2DatastoreService`** (`core\storage\MorpheusVmeQcow2DatastoreService.java`) gives the built-in directory/QCOW2 volume logic (`createVolume`, `cloneVolume` x2, `resizeVolume`, `removeVolume`) on `datastore.externalPath`. Use it when your datastore *is* a filesystem.
- **`morpheus.services.cloud.datastore`** is the Datastore CRUD service. `morpheus.services.cloud.datastoreLocation` (`DatastoreLocation{datastore, refType, refId, refUUID, status, statusMessage}`) tracks per-host status.
- **`model\Datastore.java`:** `name, type, category, externalId, uniqueId, externalPath, refType, refId, storageSize, freeSpace, online, active, allowProvision, heartbeatTarget, defaultStore, storageVolume (backing LUN!), storageServer, volumeType, datastoreType, status, statusMessage, rawData, locations`, plus `configMap`.

### 3.2 Model (a): one big LUN per datastore, formatted GFS2 (VMFS-like)

**How the built-in GFS2 type works.** In 9.1 the UI calls it "HPE Clustered Datastore (Shared LUN)"; the REST/older name is "GFS2 Pool", externalType `dir:gfs2`.

- **UI:** Infrastructure > Storage > Data Stores > Add, or the cluster's Datastores tab. The fields are Name, Cluster, Block Device and Heartbeat Target.
- **REST:** `POST /api/clusters/{id}/datastores` with `{datastore:{name, datastoreType:{code|id}, config:{blockDevice:"<path>", allowReformat:false}, heartbeatTarget, supportsVmSecureMetadata, defaultStore, ...}}`. `config.blockDevice` is the only storage input. Get the exact type `code` from the new `GET /api/data-store-types`.
- **Block device path:** it must be the WWN form, **`/dev/mapper/3<wwn>`**, never `mpathX`, because positional names differ across hosts and break mounts. For Pure that is `/dev/mapper/3624a9370<serial>`.
  - The device must be visible on **every** host.
  - Pure's guide warns: "If any host lacks the multipath device, the device is missing from the dropdown with no error message", and "VME Manager does not rescan hypervisors automatically" before 9.1.
- **What core runs** (docs `infrastructure/clusters/hvm/storage_operations.html`):
  - "mkfs.gfs2 is run with lock_dlm protocol, 2048 resource groups, and 32 initial journals"
  - `gfs2_jadd` when hosts exceed journals
  - mount on all hosts
  - a libvirt pool per host
- **Cluster stack:** on layouts 1.3/2.0 it is Corosync + DLM, with the **Morpheus agent's QuorumCheckService doing quorum and fencing** (self-fence by reboot). Pacemaker is gone, though legacy layout 1.2 / Ubuntu 22.04 used Pacemaker. The dropped `MorpheusGfs2DatastoreService` javadoc's "pacemaker stonith" wording therefore reflects the legacy layout.
- **Update:** datastore update (PUT) has **no config fields**. Block device and size cannot be changed through the API.
- **Limits:** 50 GFS2 datastores per cluster. A standard cluster needs 3 hosts minimum; 2-node needs a Distributed Worker witness plus a GFS2 datastore, on layout ≥1.3.

**What the plugin owns in this model:** the LUN lifecycle and host connectivity, not the filesystem.
1. Create the Pure volume and connect it to the cluster's Pure host group.
   - iSCSI sessions should already exist, either via Cluster > Storage > iSCSI (portal only, no iface binding or CHAP in the UI) or via the plugin's host package.
   - Rescan every host (`iscsiadm -m session --rescan`, then `multipath -r`; or `hvmcli storage rescan --iscsi` on layout 2.0).
   - Wait until `/dev/mapper/3624a9370<serial>` exists with the same size on all hosts.
2. Hand the device path to the **built-in** GFS2 type.

There are three ways to do step 2. None is fully supported yet:

- **(i) Two-step UX (recommended for v1).**
  - The plugin provides a "Provision GFS2 LUN" action: a cluster tab button or a task/workflow (sections 6, 8). It creates and maps the LUN, then calls the REST API (`POST /api/clusters/{id}/datastores`, datastoreType GFS2, `blockDevice` = the multipath path) or tells the operator which path to pick.
  - Morpheus then owns the GFS2 datastore natively, including HA heartbeat, image target, live migration and secure-metadata, because it *is* a native GFS2 datastore.
  - The plugin keeps a link through `StorageVolume.wwn` and `Datastore.storageVolume` and shows array-side state (capacity, DRR, snapshots) in a cluster tab.
  - Calling the REST API from inside a plugin needs a service-account token stored as a credential. Alternatively, build the `Datastore` record and call `morpheus.services.storage.datastoreType.createDatastore(ds)` from a controller or task, **not** from inside your own `createDatastore`.
- **(ii) Plugin datastore type that delegates.**
  - Your `DatastoreTypeProvider.createDatastore()` creates the LUN, then calls `morpheus.services.storage.datastoreType.createDatastore()` on a GFS2-typed datastore (or `vmeQcow2DatastoreService` for volume ops after mounting the filesystem yourself).
  - HPE hit a **deadlock** doing exactly this for Alletra: "plugin's createDatastore() runs inside DatastoreTypeService lock and needs to delegate host-side operations to gfs2DatastoreService" (PR #203 body). Their fix was reverted, so expect this to block until a new API ships.
- **(iii) Do it yourself.**
  - Your datastore type runs `mkfs.gfs2 -p lock_dlm -t <cluster>:<fs> -j <nodes+1>`, mounts it on every host (or adds a pacemaker Filesystem resource), defines a libvirt `dir` pool, sets `datastore.externalPath`, and delegates all volume ops to `vmeQcow2DatastoreService`.
  - This **duplicates and can fight Morpheus's own Corosync/DLM + agent-quorum configuration**, and you lose the built-in type's heartbeat and fencing integration. Avoid it.

**Pros of model (a):**
- It is the supported, tested HVM path: live migration, HA restart, heartbeat target and image cache all work on GFS2 without plugin hooks.
- Array object count stays low: a few LUNs per cluster.
- Snapshots and clones are QCOW2-native, so Morpheus VM snapshots keep working.
- It matches the current manual Pure-on-VME practice, which an HPE community thread calls "not supported officially", but the GFS2 path itself is supported.

**Cons of model (a):**
- No per-VM array snapshots or replication. A Pure snapshot of the LUN is crash-consistent for *all* VMs on it, and restoring one VM means cloning the LUN snapshot, connecting it, mounting it read-only somewhere and copying the qcow2. Mounting a GFS2 clone needs a different lock table, or `lock_nolock` on a single host. See section 7.
- GFS2 locking overhead and fencing sensitivity.
- Growing the datastore means a Pure resize, then rescan plus `multipathd resize map`, then `gfs2_grow` on one node. Morpheus has no extend API, so the plugin must do it and then trigger a datastore/cluster refresh (`GET /api/clusters/{id}/refresh`).

### 3.3 Model (b): one LUN per virtual disk (vVol/RDM-like, the Alletra MP design)

Flow, per `StorageDatastorePlugin.adoc` lines 376-626:
1. **Create VM:**
   - `createVolume()` for data disks, or `cloneVolume()` for the root disk from the cached QCOW2 image.
   - `prepareHostForVolume()` once per volume: connect the LUN to the host group, run `iscsiadm -m session --rescan` (or `rescan-scsi-bus.sh`), wait for multipath, and set `volume.externalId` and `volume.setConfigProperty('source.dev', '/dev/disk/by-id/dm-uuid-mpath-<wwid>')`.
   - `buildDiskConfig()` returns `diskType='block'`, `DiskMode.VIRTIO`/`VIRTIO_SCSI`. This yields `<disk type="block"><driver type="raw" cache="none" io="io_uring" discard="unmap"/><source dev=.../>`.
   - libvirt then defines and starts the VM.
2. **Destroy VM:** stop → `removeVolume()` (disconnect, then delete on the array; Pure keeps eradication-pending for 24h) → `releaseVolumeFromHost()` (`multipath -f`, then `echo 1 > /sys/block/sdX/device/delete`).
3. **Clone from image:** create the LUN, map it, then `qemu-img convert -f qcow2 -O raw -n --target-is-zero -W -m16 <cache.qcow2> <dev>` on the host. Or, better for Pure, keep a "golden" raw LUN per image and use **Pure `POST /volumes?overwrite` / copy** (instant, dedup-free clone), which is the Ceph `mvm-image-cache` pattern from adoc lines 1186-1271.
4. **Image cache:** you still need a **directory datastore (NFS or GFS2)** for the QCOW2 image cache (adoc lines 47-57, best-practice #7). `cloneVolume(..., VirtualImage, CloudFileInterface)` handles the no-cache case through `MorpheusFileCopyService`.
5. **Resize:** Pure resize → rescan and `multipathd resize map <wwid>` on **all** hosts → `virsh blockresize`. Shrink is not allowed.

**Live migration and HA expectations:**
- "Block devices (vDISKs) must be accessible from ALL hypervisor hosts in the cluster at all times. The HVM high-availability failover code does not invoke any plugin hooks" (adoc line 45).
- So `prepareHostForVolume` must make the device visible on *every* host, in parallel, returning the provisioning host's result (adoc line 556). Connecting to a Pure **host group** containing all cluster hosts does this in one API call; then you only rescan per host.
- The device path must be identical on all hosts. Use the WWID path, `/dev/mapper/3<wwn>` (Morpheus's own convention) or `/dev/disk/by-id/dm-uuid-mpath-3<wwn>`, together with `user_friendly_names no`.
- HPE publishes a cap of about 800 LUN-per-vDisk volumes per cluster (`hvm/maximums.html`).
- On file-based datastores, VMs with snapshots cannot be storage-migrated. Datastore Explorer and Storage DRS (Datastore Groups) work only on file-based datastores, so LUN-per-vDisk VMs lose those features.
- `DatastoreEvent.SERVER_MOVE` (live-migration notification) and `VOLUME_ATTACH`/`VOLUME_DETACH` events exist if you need them.

**Pros of model (b):**
- Per-VM (per-disk) Pure snapshots, protection groups, replication and QoS.
- Instant array clones.
- Restore of one VM equals volume overwrite from a snapshot.
- No GFS2/DLM in the data path. This is what the Alletra MP / Storage Integration Pack does.

**Cons of model (b):**
- Many Pure volumes and connections (FlashArray limits on volumes and on connections per host group).
- Every operation runs host commands across the whole cluster.
- Plugin bugs directly break provisioning.
- VM snapshots are only what you implement.
- You still need GFS2/NFS for images, ISOs and secure-metadata (`supportsVmSecureMetadata` is "GFS2 and NFS only").
- Much more code: easily 5-10x model (a).

**Recommendation:** ship model (a) first as a "LUN lifecycle + GFS2 helper + array visibility" plugin (StorageProvider + cluster tab + tasks + optional array-snapshot BackupProvider). Consider model (b) later as a separate datastore type for tier-1 VMs once HPE re-lands a GFS2 or volume-group API.

---

## 4. Running commands on HVM hosts

`core\MorpheusContext.java`:
```java
Single<TaskResult> executeCommandOnServer(ComputeServer server, String command);                                               // :624
Single<TaskResult> executeCommandOnServer(ComputeServer server, String command, Boolean rpc, String sshUsername, String sshPassword,
        String publicKey, String privateKey, String passPhrase, Boolean noProfile, Boolean sudo);                              // :641
Single<TaskResult> executeCommandOnServer(ComputeServer server, String command, Boolean rpc, String sshUsername, String sshPassword,
        String publicKey, String privateKey, String passPhrase, Boolean noProfile, Boolean sudo, Boolean guestExec);           // :667
Single<TaskResult> executeSshCommand(String address, Integer port, String username, String password, String command, String publicKey,
        String privateKey, String passPhrase, Boolean ignoreExitStatus, LogLevel logLevel, Boolean doPty, String runAsUser, Boolean sudo); // :574
Single<String> acquireLock(String name, Map opts) / releaseLock(name, [lock:id]) / checkLock                                    // distributed locks
```
- **Routing:** "automatically ensure the command is executed through the right medium (such as through an Edge Distributed Worker and through the agent with proper ssh fallback)" (`DatastoreTypeProviders.adoc` line 63; also "morpheus agent, an edge gateway worker, or direct SSH", `StorageDatastorePlugin.adoc` line 682). `rpc=true` forces SSH or WinRM instead of the agent. `guestExec=true` uses the QEMU guest agent, which is for VMs, not hosts.
- **Privilege:** HPE's own code prefixes `sudo` inside the command string (Ceph examples: `sudo rbd ...`, `sudo virsh ...`; javadoc `sudo virsh pool-refresh`). Use `sudo` explicitly. Running as root through the agent is not documented.
- **Timeouts:** there is no timeout parameter. Wrap the Single yourself, as the OLVM plugin does: `.timeout(seconds, TimeUnit.SECONDS).blockingGet()`. Bound parallelism with your own executor (best-practice #1).
- **Result:** `model\TaskResult.java`: `Boolean success; Object data; String output; String exitCode; String msg; String error; List results; Map customOptions`. Read `result.success`, `result.output` (stdout) and `result.exitCode`. The adoc's `result.data` is for older usage.
- **Getting a cluster's hosts:**
  ```groovy
  ComputeServerGroup cluster = morpheus.services.cluster.get(datastore.refId)          // or ClusterEvent.cluster
  List<ComputeServer> hosts = morpheus.services.computeServer.list(new DataQuery()
        .withFilter('serverGroup.id', cluster.id)
        .withFilter('computeServerType.nodeType', 'morpheus-kvm-node'))   // nodeType string is from the adoc example (line 1106) - verify on 9.1 via GET /api/servers?clusterId=
  ```
  `ComputeServerGroup.servers` also exists, but it is often a lazy or projection collection, so prefer the query.
- **Host IQN:** `ComputeServer.iScsiInitiatorName` exists (`model\ComputeServer.java:175`, getter `getiScsiInitiatorName()`). Whether HVM populates it on 9.1 is unverified, so fall back to `sudo cat /etc/iscsi/initiatorname.iscsi` through `executeCommandOnServer`. Persist the IQN on a `StorageHost` (`iscsiPath`/`config`) linked through `StorageHost.hosts`.
- **REST equivalent** (ops tooling outside the plugin): `POST /api/execution-request/execute?serverId=<id>` with `{script:"..."}`, then poll `GET /api/execution-request/{uniqueId}` for `stdOut`/`stdErr`/`exitCode`.
- **Do not use Posh-SSH/JSch-style direct SSH.** The Proxmox plugin does, but that bypasses the agent and edge-worker routing and needs separate credentials.

---

## 5. Cluster lifecycle hooks and cluster packages

### 5.1 Events

**The subscriber facet** (`core\providers\PluginProvider.java:79`):
```java
interface EventSubscriberFacet<E extends Event> {
    List<EventType> getSupportedEventTypes();
    default List<IEventScope> getEventScopes();      // EventScope.ALL by default
    void onEvent(E event, AccountIntegration integration);   // NOTE: void + 2 args; the adoc sample `ServiceResponse onEvent(Event)` is wrong
}
```
**Cluster events** (`model\event\ClusterEvent.java`):
- `ClusterEventType { CREATE, DELETE, UPDATE, ADD_WORKER, REMOVE_WORKER }`
- Fields: `cluster (ComputeServerGroup)`, `server (ComputeServer)`, `message`

**Datastore events** (`model\event\DatastoreEvent.java`):
- `DatastoreEventType { SERVER_MOVE, SERVER_SHUTDOWN, SERVER_STARTUP, VOLUME_ATTACH, VOLUME_DETACH, VOLUME_MOVE_INITIATED, VOLUME_MOVE_COMPLETED, VOLUME_MOVE_FAILED }`
- Fields: `sourceDatastore/targetDatastore, server, volume, sourceHost/targetHost`

**Implementing it:**
- Implement `PluginProvider.EventSubscriberFacet<Event>` on the DatastoreTypeProvider (doc pattern, lines 628-661) and return `[ClusterEvent.ClusterEventType.ADD_WORKER, ClusterEvent.ClusterEventType.REMOVE_WORKER, DatastoreEvent.DatastoreEventType.VOLUME_ATTACH, ...]`.
- On `ADD_WORKER`: install and verify open-iscsi/multipath, read the IQN, create the Pure host, add it to the host group, log in to the targets, and rescan. Existing LUNs then appear. A GFS2 journal add (`gfs2_jadd`) is core's job for the built-in type.
- On `REMOVE_WORKER`: remove the host from the host group and delete the Pure host.
- **Caveat:** it is undocumented whether ClusterEvents reach a provider that has no datastore of its type on that cluster, so filter by cluster yourself.
- `ClusterProvider` (`core\providers\ClusterProvider.java`) has `finalizeLinuxComputeServer` and similar hooks, but those are for *defining* a cluster type. Do not replace HVM's built-in cluster type.

### 5.2 ComputeTypePackageProvider ("cluster packages")

`core\providers\ComputeTypePackageProvider.java`:
```java
Icon getCircularIcon(); List<OptionType> getOptionTypes(); String getDescription(); String getType(); String getPackageType(); String getProviderType(); String getPackageVersion();
default ServiceResponse validatePackageConfig(ComputeServerGroup, ComputeServerGroupPackage);
default ServiceResponse<ComputeServerGroupPackage> installPackage(ComputeServerGroup, ComputeServerGroupPackage, PackageInstallRequest);
default ServiceResponse<ComputeServerGroupPackage> upgradePackage(ComputeServerGroup, ComputeServerGroupPackage, PackageUpgradeRequest);
default ServiceResponse<ComputeServerGroupPackage> updatePackageConfig(ComputeServerGroup, ComputeServerGroupPackage, PackageUpdateRequest);
default ServiceResponse deletePackage(ComputeServerGroup, ComputeServerGroupPackage, PackageDeleteRequest);
default HTMLResponse renderFormTemplate(ComputeServerGroup, ComputeServerGroupPackage);   // custom form (1.2.8)
```
- `getProviderType()` returns `'mvm'` (`ComputeTypePackage.ProviderType.MVM("mvm")`). `getPackageType()` returns e.g. `'storage'`.
- `ComputeServerGroupPackage` holds `packageType, enabled, installed, packageVersion, status, statusMessage, errorMessage`.
- The package appears in the cluster's add-on packages UI with install, upgrade and remove (adoc lines 1039-1161).

**Yes, a "Pure iSCSI best-practice" package fits.** Its option types would be the iSCSI VLAN NICs, Pure target portals/IQN, CHAP on/off, and whether to manage `multipath.conf`.

- **`installPackage`, per host:**
  - `apt-get install -y open-iscsi multipath-tools sg3-utils`
  - write `/etc/multipath/conf.d/pure.conf` with the Pure device stanza: `vendor "PURE"`, `product "FlashArray"`, `path_grouping_policy group_by_prio`, `path_selector "service-time 0"`, `hardware_handler "1 alua"`, `prio alua`, `failback immediate`, `fast_io_fail_tmo 10`, `dev_loss_tmo 60`, `no_path_retry 0`, plus `user_friendly_names no` in defaults. Use a conf.d drop-in, not a rewrite of `/etc/multipath.conf`, so you don't clobber Morpheus's settings.
  - create `iscsiadm` ifaces bound to each NIC, run discovery and login, set `node.startup=automatic`, and set `node.session.timeo.replacement_timeout`.
  - add udev rules for Pure (`max_sectors_kb`, scheduler `none`).
  - `systemctl enable --now iscsid multipathd`.
- **`updatePackageConfig`/`upgradePackage`:** re-apply.
- **`deletePackage`:** log out only when no Pure-backed datastore exists.
- **A "validate/compliance" mode** does read-only checks and puts per-host results in `statusMessage`: sessions per portal, `multipath -ll` path count and ALUA state, conf values, NIC MTU (`ping -M do -s 8972`).
- `builtbyfood/morpheus-vme-iscsi-setup` (MIT, `sdk\ext-morpheus-vme-iscsi-setup\`) is a 64 KB bash reference for the NIC↔portal pairing, safe path logout and the `user_friendly_names no` requirement for GFS2.
- **Caveat:** the package model is cluster-level. New hosts are not auto-installed, so pair it with the `ADD_WORKER` handler. Check `ComputeServerGroup.packages` there and re-run install on the new host.
- **Overlap to verify:** whether 9.1 host profiles (`/api/host-profiles` with `compliance`/`remediate`, new in 9.1) already cover part of this.

---

## 6. UI extension points

All tab providers extend `UIExtensionProvider { ContentSecurityPolicy getContentSecurityPolicy(); Renderer<?> getRenderer(); }`. The `Abstract*TabProvider` classes give a `HandlebarsRenderer("renderer", classLoader)` with `{{asset}}`, `{{nonce}}` and i18n helpers. Templates live in `src/main/resources/renderer/hbs/*.hbs` and are rendered with `getRenderer().renderTemplate("hbs/clusterTab", new ViewModel(object: cluster))`.

| Provider | Signature | Where it shows |
|---|---|---|
| `ClusterTabProvider` / `AbstractClusterTabProvider` | `HTMLResponse renderTemplate(ComputeServerGroup cluster); Boolean show(ComputeServerGroup cluster, User user, Account account)` | Infrastructure > Clusters > cluster detail. This is the main place for "Pure: host group, LUNs, path health, Check host compliance, Provision GFS2 LUN". |
| `ServerTabProvider` / `AbstractServerTabProvider` | `renderTemplate(ComputeServer)`, `show(ComputeServer, User, Account)` | host or VM detail (per-host iSCSI sessions/paths) |
| `InstanceTabProvider` / `AbstractInstanceTabProvider` | `renderTemplate(Instance)`, `show(Instance, User, Account)` | instance detail ("Restore from FlashArray snapshot") |
| `VolumeTabProvider` / `AbstractVolumeTabProvider` | volume detail (`samples\morpheus-volume-tab-plugin` in core) | |
| `NetworkTabProvider`, `NetworkRouterTabProvider` (1.3.0 "router tabs"), `NetworkServerTabProvider`, `AppTabProvider`, `BackupIntegrationTabProvider` | similar | |
| `SystemTabProvider` / `AbstractSystemTabProvider` (1.4.0) | `getCode(); getName(); getOrder(); renderTemplate(System); show(System, User, Account)` | for the new "Systems" objects (HPE system layouts), not clusters |
| `GlobalUIComponentProvider` | `Boolean show(User, Account); HTMLResponse renderTemplate(User, Account)` | injected on every page (global banner/widget). Sample: `samples\morpheus-global-ui-plugin`. |
| `StorageProvider.renderTemplate(StorageServer)` | | replaces the storage-server detail view (array dashboard) |
| `ActionProvider` (1.5.0) | `String getKey(); default String getNamespace(); ActionState getState(ActionRequest); ServiceResponse<ActionResponse> runAction(ActionRequest); ServiceResponse<HashMap<String,Object>> prepare(ActionRequest)` with `ActionRequest{namespace,key,refType,refId,refIds,user,data}`, `ActionType{code,providerCode,name,category,sortOrder,wizard}` | Release notes say "contribute custom actions to **system component layouts**". Whether it adds buttons to the cluster/server/instance Actions menu is unverified. |
| `DatastoreTypeProvider` "datastore type actions" (1.2.13 note) | | no public interface found in 1.5.0. Treat as unavailable. |

There is **no dashboard-widget provider** in 1.5.0. Use `GlobalUIComponentProvider`, `ReportProvider` or `AnalyticsProvider` instead.

### Buttons that post back to the plugin
1. Register a `PluginController` (`web\PluginController.java`: `List<Route> getRoutes()`) through `this.controllers.add(new PureController(this, morpheus))` in `Plugin.initialize()`.
2. Define routes with `web\Route.java`: `Route.build(String url, String method, Permission|List<Permission>)`. Routes are served under **`/plugin/<url>`**; the sample form posts to `/plugin/customTab/example`. The handler method receives `ViewModel<Map>` (with `model.object` as the request params/JSON, `model.user`, and the request/response objects, which are jakarta since 1.4.0). It returns `HTMLResponse.success(...)` or `JsonResponse.of(map)`. Source: `samples\...\morpheus-tab-plugin\...\CustomTabController.groovy`.
3. Gate routes with plugin-defined permissions. Override `List<Permission> getPermissions()` in the Plugin class: `new Permission('Pure Storage Ops','pureStorageOps',[Permission.AccessType.full])` (`TabPlugin.groovy`). Then grant it in roles, and also check `show()` per user or account.
4. In the `.hbs` file, include `<input type="hidden" name="_csrf">` (sample), load JS with `<script src="{{asset "/pure-tab.js"}}" nonce="{{nonce}}">`, and return a `ContentSecurityPolicy` from the provider if you load external resources.
5. Long operations should not block the HTTP request. Kick off work (a Morpheus process or task) and poll a JSON route. `ProcessJobProvider` (1.4.0) and `morpheus.services.process` (1.5.0 adds `updateProcessMessage`/`updateProcessError`) give UI-visible progress history.
6. For drop-downs in option types, register a `DatasetProvider` / `AbstractDatasetProvider` (Alletra prototype `AlletraStorageServerDataSetProvider.groovy`) and reference it as `optionSource: '<namespace>.<key>'`.

---

## 7. Snapshots and backups

### 7.1 VM snapshots (DatastoreTypeProvider facets)
- **Trigger:** the normal instance "Snapshot" action. REST equivalents are `PUT /api/instances/{id}/snapshot {snapshot:{name,description}}`, revert with `PUT /api/instances/{id}/revert-snapshot/{snapshotId}`, delete with `DELETE /api/snapshots/{id}`, and host-level `PUT /api/servers/{id}/snapshot` (no host revert).
- **Dispatch:** for every volume on a plugin datastore type, the UI calls that type's `SnapshotServerFacet.createSnapshot(server, forBackup, forExport)`, or `SnapshotInstanceFacet` for instance-level. It aggregates across datastore types into one parent `Snapshot` (adoc lines 736-750).
- **Plugin's job:** build `Snapshot{name, externalId}` plus `SnapshotFile{name, externalId, type:'block', volume, diskIndex, exportPath}` and return them. The UI persists them.
- **Revert:** the UI powers the VM off, calls `revertSnapshot`, re-runs `applyDiskConfig` and redefines the VM, then powers it on.
- **`forExport=true`:** map the snapshot volume to the host and set `exportPath`, so morphd can stream it to a backup target (adoc lines 833, 925-946).
- **Model (a) implication:** on a built-in GFS2 datastore, these facets are core's (qcow2 `virsh snapshot-create-as --disk-only`). A plugin cannot intercept snapshots of VMs on a native GFS2 datastore. Array snapshots there must go through a separate BackupProvider, task or action.
- **Model (b) implication:** implement `SnapshotServerFacet` with Pure **protection-group snapshots** (`POST /protection-group-snapshots` over a pgroup containing the VM's volumes) for crash consistency across disks. Optionally run `virsh domfsfreeze`/`domfsthaw` around it (Ceph pattern, adoc lines 1393-1437). Revert by `POST /volumes?overwrite=true` from the snapshot, then re-discover devices.
- **Stubbing:** implement `SnapshotInstanceFacet` with error stubs if unsupported, so it doesn't fall back to defaults (adoc lines 696-733).

### 7.2 BackupProvider for array-snapshot backups

Interfaces (`core\backup\*.java`):
- `BackupProvider` / `AbstractBackupProvider`: provider-level, with `refresh(BackupProvider)` and 1.4.0 `refreshIntervalHours`/`nextRunDate`.
- `BackupTypeProvider` / `AbstractBackupTypeProvider`, scoped per provision type with `addScopedProvider(typeProvider, '<provisionTypeCode>', null)`, where `kvm` = HVM.
  - Flags: `getRestoreExistingEnabled(); getRestoreNewEnabled(); getRestoreType(); getRestoreNewMode(); getCopyToStore(); getDownloadEnabled()`.
  - `getExecutionProvider()` returns a `BackupExecutionProvider`:
    ```java
    configureBackup / validateBackup / createBackup / updateBackup(1.5.0) / deleteBackup / deleteBackupResult / prepareExecuteBackup / prepareBackupResult
    ServiceResponse<BackupExecutionResponse> executeBackup(Backup, BackupResult, Map executionConfig, Cloud, ComputeServer, Map opts)
    ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult); cancelBackup; extractBackup
    ```
  - `getRestoreProvider()` returns a `BackupRestoreProvider`:
    ```java
    configureRestoreBackup(BackupResult, Map config, Map opts); getBackupRestoreInstanceConfig(BackupResult, Instance, Map restoreConfig, Map opts)
    validateRestoreBackup(BackupResult, Map); getRestoreOptions(Backup, Map)
    ServiceResponse<BackupRestoreResponse> restoreBackup(BackupRestore, BackupResult, Backup, Map opts); refreshBackupRestoreResult(BackupRestore, BackupResult)
    ```
- **Shortcut:** extend `MorpheusBackupProvider` (`core\backup\MorpheusBackupProvider.java`). Morpheus then manages jobs and schedules and only delegates execute/restore to your type provider (`BackupPlugins.adoc` lines 208-227; examples are the DigitalOcean and Rubrik plugins).
- 1.5.0 adds `hasEditJob, hasRunJob, hasEditableJobCode` flags and `updateBackup`.
- `isSnapshot` was added to `BackupTypeProvider` in 1.1.5, which marks snapshot-style backups.

**Restore to new instance:** this is supported by the framework. Set `getRestoreNewEnabled()=true`. REST restore takes `{restore:{backupResultId, restoreInstance:"new"|"existing", instanceId, siteId, config, instanceConfig}}`.

- **Model (b):** restore-new means Pure-copying the snapshot to new volumes and letting the provision flow attach them. That needs `getBackupRestoreInstanceConfig` to point the new instance's volumes at the copies. Complex but feasible.
- **Model (a):** a per-VM restore from a LUN snapshot requires these steps:
  1. Copy the snapshot to a temp volume.
  2. Connect it to one host.
  3. Mount the GFS2 clone. This needs `-o lockproto=lock_nolock` on a single host, or a `tunegfs2 -U`/`-o locktable` change. Unverified on the HVM build.
  4. `qemu-img convert` the VM's qcow2 back onto the live datastore.
  5. Unmount, then disconnect and destroy the temp volume.

  That is a good fit for a **TaskProvider/workflow** or controller action rather than a BackupProvider, at least initially.
- Morpheus's native HVM backups ("morpheusVmBackup") and Veeam for VME already exist. An array-snapshot provider is mainly for fast restore and replication, not for retention.

---

## 8. Tasks and workflows

**Yes, plugins can provide task types.** Implement `TaskProvider` (`core\providers\TaskProvider.java`):
```java
TaskType.TaskScope getScope();   // e.g. TaskScope.all / app / instance / server
String getDescription(); Boolean isAllowExecuteLocal(); Boolean isAllowExecuteRemote(); Boolean isAllowExecuteResource();
Boolean isAllowLocalRepo(); Boolean isAllowRemoteKeyAuth(); Boolean hasResults(); List<OptionType> getOptionTypes(); Icon getIcon();
TaskResult executeLocalTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance);
TaskResult executeServerTask(ComputeServer server, Task task, Map opts);  TaskResult executeServerTask(ComputeServer server, Task task);
TaskResult executeContainerTask(Workload workload, Task task, Map opts);  TaskResult executeContainerTask(Workload workload, Task task);
TaskResult executeRemoteTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance); ...
@Deprecated ExecutableTaskInterface getService();   // old style: extend AbstractTaskService
```
- **Sample:** `samples\morpheus-task-plugin\...\ReverseTextTaskProvider.groovy`.
- **Reading inputs:** option values arrive in `task.taskOptions` / `opts`. With `hasResults()=true`, the output can be chained into later workflow phases (0.12.5 note).
- **Proposed task types:** "Pure: create and map LUN to cluster", "Pure: rescan cluster", "Pure: grow GFS2 datastore", "Pure: snapshot datastore/pgroup", "Pure: clone snapshot to new datastore", "Pure: restore VM disk from snapshot", "Pure: host compliance check".
- **Execution paths:** these run in Morpheus workflows (task sets), from `POST /api/tasks/{id}/execute` and `POST /api/task-sets/{id}/execute` with `{job:{targetType:'server'|'instance'|'appliance', servers:[...], customOptions:{...}}}`, on schedules (`/api/execute-schedules`), and from the cluster tab through the controller.
- **Scope gap:** task scope has no "cluster" target. Run it locally and take a cluster id option, backed by a `DatasetProvider` listing HVM clusters, or target a host server.
- 1.4.0's `ProcessJobProvider` / process-step API and 1.4.0 "Orchestration" providers are alternatives for multi-step jobs with visible progress, but they are newer and less documented.

---

## 9. Constraints, gotchas and version traps (from SDK source and history)

1. **GFS2 plugin API:** release notes advertise `MorpheusGfs2DatastoreService` (1.4.0) and `StorageVolumeProvisioningFacet` (1.4.2), but neither is in the published jars. `MorpheusGfs2FilesystemService` (setup/cleanup/refresh with `Gfs2SetupRequest{datastore, serverGroup, storageVolume, devicePath, clusterName, lockTableName, executionMode AGENT|PACEMAKER, formatIfExists}`) was merged then reverted (PR #203 → #210, 2026-07-10). Watch `v1.5.x` for its return. Its shape tells you what HPE thinks a plugin needs.
2. **Deadlock:** do not call the routing `datastoreType.createDatastore()` from inside your own `createDatastore()`; it runs under the DatastoreTypeService lock (PR #203).
3. **Signatures drifted.** `prepareHostForVolume`/`releaseVolumeFromHost` gained a `ComputeServer server` arg, `createSnapshot` gained `(forBackup, forExport)`, and `executeCommandOnServer(server, cmd, Map)` (used by the Alletra prototype) does not exist in 1.5.0. Code against the jar, not the samples.
4. **The EventSubscriberFacet doc sample is wrong.** The real signature is `void onEvent(E event, AccountIntegration integration)`.
5. **Services moved in 1.2.5:** `morpheus.services.storageVolume` became `morpheus.services.storage.volume`. Old accessors are deprecated.
6. **Generator templates are stale.** The 9.1.x template pins api 1.3.0 and min version 6.3.0. `GettingStarted.adoc` still says rxJava2 and Gradle 6.5/7. The real platform is rxJava3 and Gradle 8.13/9.4 in HPE repos.
7. **HA and live migration** need every LUN visible on every host beforehand, with an identical device path. No plugin hook runs at failover.
8. **REST limits (9.1):**
   - no datastore refresh endpoint (use `GET /api/clusters/{id}/refresh`)
   - no storage-server refresh
   - no storage hosts/groups endpoints
   - datastore update can't change GFS2 config
   - `DELETE /api/data-stores/{id}` only *initiates* the delete
   - `supportsVmSecureMetadata` allows one datastore per cluster, GFS2/NFS only
9. **Datastore type code:** the spec never states the GFS2 type code. Read it at runtime from `GET /api/data-store-types`, which is new in 9.1, or `morpheus.services.cloud.datastoreType` / `MorpheusCloudDatastoreTypeService`.
10. **Licence:** plugin-core is under the proprietary "Morpheus Data PLUGIN CORE SOURCE LICENSE" (GitHub: NOASSERTION), not OSS. Fine for building plugins, but don't vendor its source into a public repo.
11. **Pure specifics** (domain knowledge, not from Morpheus sources; verify against the Pure Linux best-practice guide for your Purity version):
    - WWID = `3624a9370` + volume serial (lowercase).
    - Use ALUA and the `service-time` selector.
    - `no_path_retry 0` is Pure's recommendation, but for GFS2 consider queueing briefly. A failed path with `no_path_retry 0` returns I/O errors that GFS2 treats as withdraw-worthy. Test it.
    - Eradication delay is 24h on destroyed volumes, so names cannot be reused immediately; use unique suffixes.
    - Host-group connections give consistent LUN IDs across hosts.

### 9b. From the Morpheus 9.x docs and release notes (docs.morpheusdata.com/en/latest = 9.1)

12. **Layouts.**
    - Legacy 1.2 is Ubuntu 22.04 with Pacemaker.
    - 1.3 is "HVM OS 24.04": agent quorum, Corosync and DLM.
    - **2.0 is "HVM OS 26.04"**, new in 9.1: Linux-bridge vswitches and `hvmcli`. It is required for confidential compute.
    - 9.0.x supports only 24.04-based layouts. 9.1 supports 1.3 and 2.0.
    - Upgrade path: host > Actions > Update > "HVM Cluster 1.3 Upgrade".
    - Host commands must handle both layouts. `hvmcli storage ...` exists only on 2.0.
13. **No GFS2 grow in 9.1.** "No datastore-level Grow Filesystem action in the UI or API." The manual procedure is:
    1. Extend the LUN.
    2. Rescan **every** host.
    3. Confirm identical size and WWN everywhere.
    4. Run `gfs2_grow <mountpoint>` once, from one (coordinator) host.

    Never run it on the raw device, never from several hosts, never `fsck.gfs2`, and never shrink. A community report says Morpheus keeps showing the old capacity afterwards; the workaround was remove and re-add with the same mount point, unverified on 9.1.
    - **This is a prime plugin task:** "Grow GFS2 datastore" = Pure resize → parallel rescan + `multipathd resize map` → verify → `gfs2_grow` on one host → `GET /api/clusters/{id}/refresh`.
14. **Host add and remove.**
    - Adding a host: iSCSI targets defined in the UI auto-login on it and `gfs2_jadd` runs. The new host's IQN still has to be added to the Pure host group *first*, which is a plugin `ADD_WORKER` job, or the auto-login sees no LUNs.
    - Removing a host: it unmounts GFS2 first. "Do not manually edit Corosync or Agent quorum files."
15. **HA mechanics.**
    - `hb.properties` is written every 20 s to the single heartbeat datastore.
    - A host is declared failed after 7 missed cycles (about 140 s) plus a failed ping to HTTPS port 7443.
    - **All-paths-down:** after 6 failed heartbeat writes (about 2 min) a host destroys its non-exempt VMs.
    - A host that loses quorum self-fences by reboot. No IPMI or watchdog fencing is mentioned.
    - **Implication:** keep Pure `no_path_retry` and `replacement_timeout` short enough that paths fail over well inside about 2 min. Do not create or modify datastores while paths are down ("Do not create a datastore ... while all paths are failed").
16. **9.1.0 known issues.**
    - A GFS2/DLM kernel defect on HVM OS 24.04 *stretch* clusters causes guest I/O errors or read-only filesystems after failover; it is fixed in the 26.04 kernel, pending validation.
    - A failed NFS pool start shows 100% usage.
    - `qemu-img: Could not open` errors appear after an image-cache purge.
    - Fixed in 9.1.0: GFS2 heartbeat fencing that "could stop VMs or prevent HA failover".
    - Older reports of "datastore Failed to create" on 8.0.10 were resolved by running `wipefs` on the LUN.
17. **Troubleshooting hooks** a compliance tab can surface: `/sys/fs/gfs2/*/withdraw`, `dlm_tool ls/status`, `multipath -ll` path count, `iscsiadm -m session -P1`. "Do not acknowledge a fence manually unless HPE Support has verified host isolation."
18. **iSCSI UI limits.** It takes target IP and port only. There is no CHAP and no iface binding, and Pure's guide says per-fabric portal filtering is unsupported in the UI. Dual-fabric setups need per-host `iscsiadm` iface binding, `node.startup automatic`, 4 sessions per host, and MTU 9000 (Pure's "HPE VME iSCSI best practices" pages on support.everpuredata.com).
19. **Multipath conflict.**
    - Morpheus installs and configures multipathd during cluster provisioning. The exact `multipath.conf` it writes is undocumented.
    - The Alletra MP guide requires `find_multipaths yes` and `user_friendly_names no`.
    - Pure's VME page shows `user_friendly_names yes`, which contradicts Morpheus's WWN-path rule. **Use `user_friendly_names no`.**
    - Add Pure settings as a `devices { device { vendor "PURE" ... } }` drop-in in `/etc/multipath/conf.d/` rather than rewriting `multipath.conf`. Re-check after layout upgrades and 9.1 Host Storage Interfaces actions such as `hvmcli storage multipath configure-default|apply --force`, which may overwrite it.
20. **Snapshots on GFS2** are external qcow2 overlays. They capture no memory state, and a revert restarts the VM. Delete is a block-commit. 9.1 adds VSS via the QEMU guest agent. VMs with snapshots cannot be storage-migrated.
21. **Other backup paths for HVM:**
    - Morpheus native HVM backups (full, incremental, synthetic full, to a bucket).
    - Veeam's own "Plug-in for HPE Morpheus VM Essentials" (VBR 13.0.1+). The Morpheus Veeam plugin does *not* cover HVM.
    - Commvault (supports out-of-place restore to an HVM cluster).
    - Zerto: "Do not select an HVM Cloud as a Zerto replication provider."
22. **Plugin install.** Administration > Integrations > Plugins, then upload the `.jar`. Marketplace search shows only plugins compatible with the current appliance and needs outbound HTTPS to share.morpheusdata.com. 9.1 moved Veeam, Commvault and UpCloud out of core into plugins, and adds `ui['excluded_plugins']` in `morpheus.rb`.

---

## 10. Reference implementations

| Repo | What it does | Licence | Completeness | Use as template? |
|---|---|---|---|---|
| **morpheus-plugin-core docs** `StorageDatastorePlugin.adoc` (+ `DatastoreTypeProviders.adoc`) | Full design guide for an HVM block-storage plugin: provider split, lifecycle order, MvmProvisionFacet, events, snapshots (UI vs plugin responsibilities), cluster packages, and the built-in **Ceph RBD** implementation as a worked example with real host command sequences | PSL (proprietary source licence) | Docs plus code fragments, but written by HPE against the current API and covering both directory and block models | **Yes, the primary design reference** |
| `HewlettPackard/morpheus-plugin-samples` | Tab, cluster tab, server tab, controller (`CustomTabController`), task, global UI, reports, system example (React tabs), cypher, approvals, guidance | Apache-2.0 | Working but old (api 1.1.6) | For UI, controller and task scaffolding; update imports and signatures |
| Generator (`morpheus-plugin-site\...\project-templates\9.1.x\groovy\` - `datastoreType\DatastoreTypeProvider.groovy.hbs`, `storage\StorageProvider.groovy.hbs`, `clusterTab`, `serverTab`, `instanceTab`, `backup\*`) | Skeletons with every method stubbed | PSL | Stubs, pinned to api 1.3.0 | **Best starting skeleton**: generate with datastoreType + storage + clusterTab + backup, then bump to 1.5.0 / JDK 25 / Groovy 4 per section 1.2 |
| `bhagyashree-charudatta-sarawate/morpheus-hpe-alletra-mp-plugin` (`sdk\alletra-mp\`) | LUN-per-vDisk prototype: StorageProvider + StorageProviderVolumes (Alletra server type with `CREDENTIAL` option types), DatastoreTypeProvider + MvmProvisionFacet + SnapshotServerFacet, DatasetProvider filtering storage servers by type code | **No licence** (all rights reserved), 0 stars, last push 2024-09-19 | Stubs. Every method returns null except one `virsh pool-refresh` example. api 1.1.8-SNAPSHOT; signatures are obsolete and it does not compile on 1.5.0. README says "sample/prototype ... incomplete" | Only for the option-type and DatasetProvider patterns. Don't copy code (no licence) |
| `storpool/morpheus-storpool-datastore-plugin` (`sdk\ext-morpheus-storpool-datastore-plugin\`) | Same provider set as Alletra, from the generator, api 1.2.3 | Apache-2.0 | 100% stubs returning "Not Implemented" | No |
| HPE `morpheus-alletramp-plugin` / "Storage Integration Pack" (Exchange, 1.0.1 → 1.14.0, min 9.1.0) | The real LUN-per-vDisk plugin: host sets (FC/iSCSI), array snapshots, 9.1 replication volume types, combined volume + GFS2 datastore workflow | Closed source (repo 404) | Production | Behavioural reference only: install it in a test environment and inspect its UI/REST behaviour and `DatastoreType` rows |
| HPE cloud plugins (`morpheus-scvmm-plugin`, `nutanix-prism`, `xenserver`, `olvm`, `proxmox-ve`) | Hypervisor cloud plugins; datastores only synced from APIs | Apache-2.0 | Production | SCVMM for the **9.1 build toolchain**; OLVM for `executeCommandOnServer(...).timeout()`; Rubrik/DigitalOcean (per `BackupPlugins.adoc`) for BackupProvider structure |
| `builtbyfood/morpheus-vme-iscsi-setup` (`sdk\ext-morpheus-vme-iscsi-setup\`) | Bash script that prepares HVM hosts for iSCSI+GFS2: iface binding, NIC↔portal pairing, safe logout, multipath | MIT, 6 stars, pushed 2026-09-22 | Complete for its scope | **Reference for the host-side commands** in a ComputeTypePackageProvider / ADD_WORKER handler |

**Recommended starting template:**
1. Generate a 9.1.x project with the datastoreType, storage, clusterTab, serverTab and backup templates.
2. Retarget it to plugin-api 1.5.0, JDK 25, Groovy 4 and gradleup-shadow, following SCVMM's `build.gradle`.
3. Implement against `sdk\jar-1.5.0` signatures, following `StorageDatastorePlugin.adoc`.
4. Lift the host-side iSCSI and multipath commands from the MIT `vme-iscsi-setup` script.
5. Start with model (a): Pure StorageProvider (hosts, host groups, volumes, sync), a "Pure iSCSI" ComputeTypePackageProvider, an ADD/REMOVE_WORKER handler, a cluster tab plus PluginController actions (provision LUN for GFS2, grow, compliance check), and TaskProviders for workflows.
6. Add an array-snapshot BackupProvider or LUN-per-vDisk datastore type later.

