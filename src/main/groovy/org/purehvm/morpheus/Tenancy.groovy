package org.purehvm.morpheus

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User

/**
 * Who may operate the storage of a cluster, for a multi-tier Morpheus (company -> environment tenant -> customers).
 *
 *  - OPERATOR: the master tenant, the tenant that OWNS the cluster (the environment tier), or a tenant listed in the
 *    plugin's "Operator tenants" setting. Operators get the cluster tab: arrays, hosts, datastores, snapshots,
 *    recovery, retire. All of it acts on storage SHARED by every tenant whose VMs live on the cluster.
 *  - CUSTOMER: any other tenant (the customer tier). Never sees the cluster tab, and every cluster route refuses it,
 *    whatever its role's "Pure FlashArray Operations" permission says (defence in depth). Customers get self-service
 *    restore of their own VMs on the instance page, behind a separate permission.
 *
 * Ownership, not tree depth, decides: the plugin API exposes no tenant hierarchy, and the tier that owns the cluster
 * is exactly the one that runs it.
 */
class Tenancy {
    /** Tenant ids from the "Operator tenants" setting (comma or space separated; names are not accepted). */
    static Set<Long> operatorTenantIds(Map settings) {
        ((settings?.operatorTenants ?: '') as String).tokenize(', ;').findAll { it.isLong() }.collect { it as Long } as Set<Long>
    }

    static boolean isOperator(User user, ComputeServerGroup cluster, Collection<Long> extraOperators = []) {
        Account a = user?.account
        a != null && (a.masterAccount == true || (cluster?.owner?.id != null && a.id == cluster.owner.id) || extraOperators.contains(a.id))
    }
}