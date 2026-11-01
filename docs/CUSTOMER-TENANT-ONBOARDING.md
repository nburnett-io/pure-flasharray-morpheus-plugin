# Onboarding a customer (tier-3) tenant onto a shared HVM cluster

What Morpheus 9.1 needs before a customer can run VMs on an HVM cluster owned by the environment tier, and restore them
with this plugin. Customers see only the cloud: never the cluster, never the plugin's cluster tab.

Tiers: **your company** (master tenant) → **environment tenant** (owns the cloud's HVM clusters, hosts and
datastores) → **customer tenants** (one per customer).

## 1. Permissions, once per environment tenant

- As master, give the environment tenant's base role **Pure FlashArray VM Restore = Full**. A tenant can only hand its
  customers what its own base role has. Do the same for **Operations** and **Administration** if the environment
  tenant's engineers operate the storage.
- In the environment tenant's multitenant user-role template, set **VM Restore = Full**, and **Operations** and
  **Administration = None**. Every new customer tenant gets a copy, with these settings locked.

## 2. Create the customer (as the environment tenant's admin)

1. **Base role.** Copy an existing customer base role, then set:
   - Cloud access Full
   - VM Restore Full, Operations None, Administration None
   - the instance types customers may use, including **HVM (`mvm`)** if they provision plain HVM VMs
   - Infrastructure: Clusters and Hosts = None, if customers should not browse infrastructure (the plugin refuses
     them either way)
2. **Tenant.** Create it with that base role; its parent is the environment tenant. The user-role template is copied in.
3. **User(s).** Create them with the copied user role. Give that role instance-type access (the template had none).
4. **Group.** The customer's group, with the cloud added.

## 3. Share the cluster's resources with the customer

| Resource | How | Gotcha |
|---|---|---|
| Cloud | Visible to the customer: a master-owned cloud with visibility **Public** | A cloud owned by the environment tenant cannot be made public (the API accepts the change and keeps it private). |
| Resource pool (the cluster) | `PUT /api/zones/{cloud}/resource-pools/{id}` `tenantPermissions.accounts` as the **environment tenant** | The list is **replaced**: include the environment tenant and every existing customer. A master-tenant update silently dropped the tier-3 tenant. |
| Network | `PUT /api/networks/{id}` `tenants` as its owner | — |
| GFS2 datastore | Cluster tab → datastore row → **Tenants…** (plugin, in-process; needs Administration) | Never edit a datastore's tenants through `/api` as a tenant user: Morpheus applies it, answers 403 and **strips the datastore's tenant entries**. |
| Hypervisor hosts | `PUT /api/servers/{id}` `visibility: public` as the environment tenant (owner) | Required. Private hosts make placement fail with "Capacity or Availability Limit Reached for Cluster". `public` on an environment-owned host shares it with that tenant's customers only. |

`PUT /api/clusters/{id}/permissions` with `tenantPermissions` returned 200 but did not persist anything, and was not
needed.

## 4. Check

- The customer user provisions into the cloud; the cluster comes from the shared pool.
- `/plugin/pure-hvm/*` cluster routes answer 403 for the customer, and the cluster page redirects away.
- The customer's VM page shows the **Restore** tab, with that VM's restore points and nothing about the storage.
