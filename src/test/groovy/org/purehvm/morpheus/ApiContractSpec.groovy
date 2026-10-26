package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import spock.lang.Specification

/**
 * The plugin is dynamic Groovy, so a renamed Morpheus API would only fail on the appliance. This spec pins
 * every Morpheus member the plugin touches against the plugin-api version in gradle.properties.
 */
class ApiContractSpec extends Specification {

    static boolean hasMethod(Class c, String name, int args = -1) {
        c.methods.any { it.name == name && (args < 0 || it.parameterCount == args) }
    }

    static boolean hasProperty(Class c, String prop) {
        String cap = prop.capitalize()
        hasMethod(c, "get${cap}") || hasMethod(c, "is${cap}") || hasMethod(c, "get${prop}") ||
                c.fields.any { it.name == prop }
    }

    def 'service accessors used by the plugin exist'() {
        expect:
        hasMethod(MorpheusContext, 'getServices', 0)
        hasMethod(MorpheusContext, 'getSettings', 1)
        hasMethod(MorpheusContext, 'executeCommandOnServer', 2)
        ['getCluster', 'getComputeServer', 'getStorageServer', 'getCloud', 'getStorage'].every { hasMethod(MorpheusServices, it, 0) }
        hasMethod(MorpheusServices.getMethod('getCloud').returnType, 'getDatastore', 0)
        hasMethod(MorpheusServices.getMethod('getCloud').returnType.getMethod('getDatastore').returnType, 'getDatastoreType', 0)
        hasMethod(MorpheusServices.getMethod('getStorage').returnType, 'getDatastoreType', 0)
        def dts = MorpheusServices.getMethod('getStorage').returnType.getMethod('getDatastoreType').returnType
        hasMethod(dts, 'createDatastore', 1)
        hasMethod(dts, 'refreshDatastore', 1)
        hasMethod(dts, 'removeDatastore', 1)
        ['get', 'list', 'save'].every { hasMethod(MorpheusServices.getMethod('getCluster').returnType, it) }
        ['get', 'list', 'save'].every { hasMethod(MorpheusServices.getMethod('getStorageServer').returnType, it) }
    }

    def 'model members used by the plugin exist'() {
        expect:
        ['name', 'parentServer'].every { hasProperty(ComputeServer, it) }
        hasMethod(ComputeServer, 'getiScsiInitiatorName', 0)
        ['type', 'cloud', 'name'].every { hasProperty(ComputeServerGroup, it) }
        hasProperty(com.morpheusdata.model.Cloud, 'account')
        hasProperty(com.morpheusdata.model.ComputeServerGroupType, 'code')
        hasMethod(ComputeServerGroup, 'getConfigProperty', 1)
        hasMethod(ComputeServerGroup, 'setConfigProperty', 2)
        ['serviceUrl', 'serviceUsername', 'servicePassword', 'credentialData', 'serverVendor', 'serverModel', 'serviceVersion', 'serialNumber',
         'externalId', 'maxStorage', 'usedStorage', 'status', 'statusMessage', 'type'].every { hasProperty(StorageServer, it) }
        ['name', 'datastoreType', 'refType', 'refId', 'cloud', 'owner', 'active', 'online', 'heartbeatTarget', 'type', 'externalPath'].every { hasProperty(Datastore, it) }
        hasMethod(Datastore, 'getConfigProperty', 1)
        ['storageSize', 'freeSpace'].every { hasProperty(Datastore, it) }
        ['get', 'save', 'create', 'remove'].every { hasMethod(MorpheusServices.getMethod('getCloud').returnType.getMethod('getDatastore').returnType, it, 1) }
        ['visibility', 'uniqueId'].every { hasProperty(Datastore, it) }
        hasProperty(ComputeServerGroup, 'owner')
        hasMethod(MorpheusServices.getMethod('getCloud').returnType.getMethod('getDatastore').returnType, 'list', 1)
        ['code', 'creatable'].every { hasProperty(DatastoreType, it) }
        ['success', 'output', 'exitCode', 'error', 'data'].every { f -> TaskResult.fields*.name.contains(f) }
        hasProperty(User, 'username')
    }

    def 'tenant permission members exist'() {
        expect:
        hasMethod(MorpheusServices, 'getResourcePermission', 0)
        ['list', 'create'].every { hasMethod(MorpheusServices.getMethod('getResourcePermission').returnType, it, 1) }
        ['account', 'morpheusResourceType', 'morpheusResourceId', 'allGroups', 'allPlans', 'allSites', 'defaultStore', 'defaultTarget']
                .every { hasProperty(com.morpheusdata.model.ResourcePermission, it) }
        com.morpheusdata.model.ResourcePermission.ResourceType.Datastore.toString() == 'Datastore'
        com.morpheusdata.model.projection.ResourcePermissionIdentity.getDeclaredField('uuid').type == String   // set by reflection
        hasProperty(Datastore, 'owner')
    }

    def 'tenancy and audit members exist'() {
        expect:
        hasMethod(MorpheusServices, 'getAdmin', 0)
        hasMethod(MorpheusServices.getMethod('getAdmin').returnType, 'getAuditLog', 0)
        hasMethod(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType, 'log', 2)
        ['account', 'user', 'actualUser', 'description'].every { hasProperty(com.morpheusdata.model.AuditLog, it) }
        com.morpheusdata.model.AuditLog.LEVEL_INFO == 1
        ['account', 'username'].every { hasProperty(User, it) }
        hasProperty(com.morpheusdata.model.Account, 'masterAccount')
        hasProperty(ComputeServerGroup, 'owner')
    }

    def 'IQN record sync members exist'() {
        expect:
        hasMethod(ComputeServer, 'setiScsiInitiatorName', 1)
        hasMethod(MorpheusServices.getMethod('getComputeServer').returnType, 'save', 1)
    }

    def 'members used by Retire exist'() {
        expect:
        ['getWorkload', 'getStorageVolume'].every { hasMethod(MorpheusServices, it, 0) }
        hasMethod(MorpheusServices.getMethod('getWorkload').returnType, 'list', 1)
        hasMethod(MorpheusServices.getMethod('getStorageVolume').returnType, 'list', 1)
        ['get', 'list'].every { hasMethod(MorpheusServices.getMethod('getComputeServer').returnType, it, 1) }
        hasProperty(com.morpheusdata.model.Workload, 'instance')
        ['volumes', 'powerState', 'parentServer'].every { hasProperty(ComputeServer, it) }
        ['datastore', 'externalId', 'maxStorage', 'usedStorage', 'rootVolume', 'name'].every { hasProperty(com.morpheusdata.model.StorageVolume, it) }
        ['active', 'online', 'freeSpace', 'storageSize', 'heartbeatTarget', 'refType', 'refId', 'externalPath'].every { hasProperty(Datastore, it) }
    }

    def 'option input types used by the plugin exist'() {
        expect:
        ['TEXT', 'PASSWORD', 'CHECKBOX'].every { n -> OptionType.InputType.fields*.name.contains(n) }
        ['helpText', 'placeHolderText', 'defaultValue', 'fieldContext'].every { hasProperty(OptionType, it) }
    }
}
