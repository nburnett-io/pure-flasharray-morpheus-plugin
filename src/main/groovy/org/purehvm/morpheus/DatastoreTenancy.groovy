package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.projection.ResourcePermissionIdentity
import groovy.util.logging.Slf4j

/**
 * The tenant permission a datastore needs so the owning tenant's users can use it through the API.
 *
 * Seen on 9.1: a UI-created GFS2 datastore carries tenants=[{id: <owner>}] (a ResourcePermission row:
 * account, type Datastore, resource id). A record created in-process without it is owned by the tenant yet every
 * tenant user gets 403 "You do not have permissions to access this api endpoint" on it - and on the cluster's
 * datastore list, because it is in the list.
 */
@Slf4j
class DatastoreTenancy {
    static final String TYPE = ResourcePermission.ResourceType.Datastore.toString()

    /**
     * Adds the owner's tenant permission when missing. Returns true if it had to be added. Best effort: a failure is
     * logged and returns false (every caller is a repair on the way to something else; the next refresh retries).
     */
    static boolean ensureOwnerPermission(MorpheusContext morpheus, Datastore ds) {
        try {
            ds.owner?.id ? ensureAccountPermission(morpheus, ds, ds.owner) : false
        } catch (Exception e) {
            log.warn("datastore ${ds.name} (${ds.id}): could not add the owner's tenant permission: ${e.message}")
            false
        }
    }

    /** The tenant entries of a datastore: [tenantId, tenantName]. */
    static List<Map> tenantsOf(MorpheusContext morpheus, Datastore ds) {
        permissions(morpheus, ds).findAll { it.account?.id }.collect { [tenantId: it.account.id, tenantName: it.account.name] }
    }

    private static List<ResourcePermission> permissions(MorpheusContext morpheus, Datastore ds) {
        morpheus.services.resourcePermission.list(new DataQuery().withFilter('morpheusResourceType', TYPE).withFilter('morpheusResourceId', ds.id))
    }

    /**
     * Shares a datastore with one more tenant (e.g. the environment tier giving a customer tenant its shared
     * datastore). In-process, because a tenant user's own /api edit of a datastore strips its tenant entries (9.1).
     */
    static boolean ensureAccountPermission(MorpheusContext morpheus, Datastore ds, Account tenant) {
        if (!tenant?.id) {
            return false
        }
        if (permissions(morpheus, ds).any { it.account?.id == tenant.id }) {
            return false
        }
        ResourcePermission rp = new ResourcePermission(account: tenant, morpheusResourceType: TYPE,
                morpheusResourceId: ds.id, allGroups: true, allPlans: true, allSites: true, defaultStore: false, defaultTarget: false)
        // core validates uuid as required, but plugin-api 1.5.0 exposes only a getter for it
        java.lang.reflect.Field f = ResourcePermissionIdentity.getDeclaredField('uuid')
        f.setAccessible(true)
        f.set(rp, UUID.randomUUID().toString())
        morpheus.services.resourcePermission.create(rp)
        log.info("datastore ${ds.name} (${ds.id}): added tenant permission for account ${tenant.id}")
        true
    }

    /** Stops sharing a datastore with a tenant. The owner's own entry is never removed. */
    static boolean removeAccountPermission(MorpheusContext morpheus, Datastore ds, Long tenantId) {
        if (tenantId == ds.owner?.id) {
            throw new IllegalArgumentException("Tenant ${tenantId} owns ${ds.name}; its access cannot be removed")
        }
        List<ResourcePermission> rows = permissions(morpheus, ds).findAll { it.account?.id == tenantId }
        rows.each { morpheus.services.resourcePermission.remove(it) }
        !rows.isEmpty()
    }
}
