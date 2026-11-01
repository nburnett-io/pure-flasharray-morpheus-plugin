package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreType
import com.morpheusdata.response.ServiceResponse
import org.purehvm.ops.Gfs2Registrar
import groovy.util.logging.Slf4j

/**
 * Creates the datastore record and routes it to the built-in GFS2 type through
 * morpheus.services.storage.datastoreType.createDatastore(). No API token and no tenant problem: the REST route only
 * works with a token from the tenant that owns the cluster (a master-tenant token gets "Cluster not found" on POST
 * /api/clusters/{id}/datastores for a subtenant's cluster, although GET works).
 *
 * The record mirrors one the UI created on 9.1: owner = the cluster's tenant,
 * refType ComputeServerGroup, uniqueId = UUID (core mounts at /mnt/<uniqueId>), config.blockDevice.
 * Called from the plugin controller, never from inside a DatastoreTypeProvider (HPE hit a deadlock there).
 */
@Slf4j
class InProcessGfs2Registrar implements Gfs2Registrar {
    final MorpheusContext morpheus

    InProcessGfs2Registrar(MorpheusContext morpheus) {
        this.morpheus = morpheus
    }

    DatastoreType gfs2Type() {
        List<DatastoreType> types = morpheus.services.cloud.datastore.datastoreType.list(new DataQuery())
        DatastoreType t = types.find { it.code == 'libvirt-dir-gfs2' } ?: types.find { it.code?.toLowerCase()?.contains('gfs2') && it.creatable }
        if (!t) {
            throw new IllegalStateException("No GFS2 datastore type registered (have: ${types*.code.join(', ')})")
        }
        t
    }

    @Override
    Long register(Long clusterId, String name, String devicePath) {
        ComputeServerGroup cluster = morpheus.services.cluster.get(clusterId)
        Account owner = cluster.owner ?: cluster.cloud?.account
        String uuid = UUID.randomUUID().toString()
        // core mounts at /mnt/<uniqueId> but leaves externalPath empty when the record does not carry it (seen on 9.1)
        Datastore ds = new Datastore(name: name, datastoreType: gfs2Type(), refType: 'ComputeServerGroup', refId: clusterId,
                cloud: cluster.cloud, owner: owner, visibility: 'public', active: true, online: true, heartbeatTarget: false,
                type: 'generic', uniqueId: uuid, externalId: name, externalPath: "/mnt/${uuid}".toString())
        ds.setConfigProperty('blockDevice', devicePath)
        ds.setConfigProperty('allowReformat', false)
        Datastore saved = morpheus.services.cloud.datastore.create(ds)
        if (!saved?.id) {
            throw new IllegalStateException('Morpheus did not save the datastore record')
        }
        // without it the owning tenant's users get 403 on this datastore and on the cluster's datastore list
        DatastoreTenancy.ensureOwnerPermission(morpheus, saved)   // Refresh capacity retries it on failure
        try {
            ServiceResponse<Datastore> r = MorpheusSession.run(morpheus) { morpheus.services.storage.datastoreType.createDatastore(saved) }
            if (!r.success) {
                throw new IllegalStateException("Morpheus GFS2 create failed: ${r.msg ?: r.errors}")
            }
            return (r.data?.id ?: saved.id) as Long
        } catch (Exception e) {
            // never leave a half-made record behind: the caller rolls the LUN back next
            try {
                morpheus.services.cloud.datastore.remove(saved)
            } catch (Exception re) {
                log.warn("could not remove datastore record ${saved.id} after a failed create", re)
                throw new IllegalStateException("${e.message} (and datastore record ${saved.id} could not be removed: ${re.message})", e)
            }
            throw e
        }
    }
}
