package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Datastore
import groovy.util.logging.Slf4j

/**
 * Brings a datastore's capacity in Morpheus up to date after the filesystem changed size, in-process (no HTTP call
 * back to the appliance). First asks the datastore's own type provider to refresh; if Morpheus still shows the old
 * size, writes the measured GFS2 size and free space onto the record.
 */
@Slf4j
class MorpheusCapacity {
    static final double TOLERANCE = 0.01

    static Map update(MorpheusContext morpheus, Long datastoreId, Long fsBytes, Long fsFree, String mountPath = null) {
        Datastore ds = morpheus.services.cloud.datastore.get(datastoreId)
        if (!ds) {
            throw new IllegalStateException("Morpheus datastore ${datastoreId} not found")
        }
        Long before = ds.storageSize
        // repairs records made by 0.4.1-0.5.1, which lacked the owner's tenant permission (tenant users got 403)
        boolean granted = DatastoreTenancy.ensureOwnerPermission(morpheus, ds)
        try {
            Datastore rec = ds
            MorpheusSession.run(morpheus) { morpheus.services.storage.datastoreType.refreshDatastore(rec) }
        } catch (Exception e) {
            log.warn("refreshDatastore(${ds.name}) failed: ${e.message}")
        }
        ds = morpheus.services.cloud.datastore.get(datastoreId)
        boolean stale = fsBytes && (!ds.storageSize || Math.abs(ds.storageSize - fsBytes) > fsBytes * TOLERANCE)
        // 0.4.1 created records without externalPath; the measured mount point is the truth
        boolean noPath = !ds.externalPath && mountPath?.startsWith('/mnt/')
        if (stale) {
            ds.storageSize = fsBytes
            if (fsFree != null) {
                ds.freeSpace = fsFree
            }
        }
        if (noPath) {
            ds.externalPath = mountPath
        }
        if (stale || noPath) {
            morpheus.services.cloud.datastore.save(ds)
        }
        [datastore: ds.name, before: before, after: ds.storageSize, externalPath: ds.externalPath,
         method: (stale ? 'record updated with measured size' : 'Morpheus refresh') + (noPath ? ', mount path recorded' : '') +
                 (granted ? ', owner tenant permission added' : '')]
    }
}
