package org.purehvm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import org.purehvm.api.PureNames
import org.purehvm.morpheus.PureClusterTabProvider
import org.purehvm.morpheus.PureController
import org.purehvm.morpheus.PureInstanceTabProvider
import org.purehvm.morpheus.PureStorageProvider
import org.purehvm.morpheus.RecoverySessions
import groovy.json.JsonSlurper

/**
 * Pure FlashArray for HVM: FlashArray host groups, GFS2 LUN lifecycle, host iSCSI/multipath compliance and
 * snapshot recovery for Morpheus HVM clusters. See OVERVIEW.md for the design.
 */
class PureFlashArrayPlugin extends Plugin {
    static final String SOURCE_URL = 'https://github.com/nburnett-io/pure-flasharray-morpheus-plugin'

    @Override
    String getCode() {
        'pure-flasharray-hvm'
    }

    @Override
    void initialize() {
        setName('Pure FlashArray for HVM')
        setDescription('FlashArray iSCSI host groups, GFS2 datastore LUNs, host compliance and snapshot recovery for HVM clusters')
        setAuthor('Pure FlashArray for HVM contributors')
        setSourceCodeLocationUrl(SOURCE_URL)
        setIssueTrackerUrl(SOURCE_URL + '/issues')
        // Required whenever the plugin has controllers: without its own renderer, the 9.1.0.1 upload parser NPEs in
        // PluginManager (renderer.addTemplateLoader) and rejects the jar with "Failed to parse plugin file".
        setRenderer(new HandlebarsRenderer('renderer', getClassLoader()))

        permissions = [
                // operators (master tenant, the cluster's owning tenant): the cluster tab; read views, full runs snapshot recovery
                Permission.build('Pure FlashArray Operations', PureController.PERM,
                        [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full]),
                // storage administrators: link arrays, host groups and host fixes, create/grow/retire/share datastores, delete files
                Permission.build('Pure FlashArray Administration', PureController.PERM_ADMIN,
                        [Permission.AccessType.none, Permission.AccessType.full]),
                // customers: restore their own VMs from array snapshots, on the instance page
                Permission.build('Pure FlashArray VM Restore', PureController.PERM_RESTORE,
                        [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full]),
        ]

        settings = [
                new OptionType(name: 'GFS2 registration mode', code: 'pure.registrationMode', fieldName: 'registrationMode',
                        fieldLabel: 'GFS2 registration mode', inputType: OptionType.InputType.TEXT, defaultValue: 'in-process', displayOrder: 0,
                        helpText: '"in-process" (default, no token, works for clusters in any tenant) or "rest" (Morpheus API; the token ' +
                                'below must belong to a user in the tenant that owns the cluster).'),
                new OptionType(name: 'Morpheus appliance URL', code: 'pure.applianceUrl', fieldName: 'applianceUrl',
                        fieldLabel: 'Morpheus appliance URL', inputType: OptionType.InputType.TEXT, displayOrder: 10,
                        placeHolderText: 'https://morpheus.example.invalid'),
                new OptionType(name: 'Morpheus API token', code: 'pure.applianceToken', fieldName: 'applianceToken',
                        fieldLabel: 'Morpheus API token', inputType: OptionType.InputType.PASSWORD, displayOrder: 20,
                        helpText: 'Only for "rest" registration: token of a service user in the tenant that owns the clusters.'),
                new OptionType(name: 'Appliance certificate SHA-256', code: 'pure.applianceFingerprint', fieldName: 'applianceFingerprint',
                        fieldLabel: 'Appliance certificate SHA-256', inputType: OptionType.InputType.TEXT, displayOrder: 30,
                        helpText: 'Pin the appliance certificate if it is self-signed; leave empty if it chains to a trusted CA.'),
                new OptionType(name: 'Add new cluster hosts automatically', code: 'pure.autoAddHosts', fieldName: 'autoAddHosts',
                        fieldLabel: 'Add new cluster hosts automatically', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'on', displayOrder: 35,
                        helpText: 'When a host joins an HVM cluster linked to a FlashArray, add it to the host group and rescan. Mounting datastores stays a button in the cluster tab.'),
                new OptionType(name: 'Operator tenants', code: 'pure.operatorTenants', fieldName: 'operatorTenants',
                        fieldLabel: 'Operator tenants', inputType: OptionType.InputType.TEXT, displayOrder: 37,
                        helpText: 'Tenant ids (comma separated) that may operate cluster storage besides the master tenant and the tenant that owns the cluster. Every other tenant is a customer: no cluster tab, only restore of its own VMs.'),
                new OptionType(name: 'Recovery session timeout (hours)', code: 'pure.recoveryTimeoutHours', fieldName: 'recoveryTimeoutHours',
                        fieldLabel: 'Recovery session timeout (hours)', inputType: OptionType.InputType.TEXT, defaultValue: '8', displayOrder: 38,
                        helpText: 'Open snapshot recovery sessions close themselves after this long (clone unmounted, disconnected and eradicated). "Keep open" in the tab restarts the clock.'),
                new OptionType(name: 'File-recovery copy: default hours', code: 'pure.fileRestoreHours', fieldName: 'fileRestoreHours',
                        fieldLabel: 'File-recovery copy: default hours', inputType: OptionType.InputType.TEXT, defaultValue: '24', displayOrder: 38,
                        helpText: 'A disk copy attached for file recovery is detached and deleted after this long, unless the user picks a longer time (up to the maximum) or keeps it longer.'),
                new OptionType(name: 'File-recovery copy: maximum hours', code: 'pure.fileRestoreMaxHours', fieldName: 'fileRestoreMaxHours',
                        fieldLabel: 'File-recovery copy: maximum hours', inputType: OptionType.InputType.TEXT, defaultValue: '72', displayOrder: 39,
                        helpText: 'The longest a user can keep a file-recovery copy attached, counted from now (also caps "Keep longer").'),
                new OptionType(name: 'Automatic compliance check (hours)', code: 'pure.complianceCheckHours', fieldName: 'complianceCheckHours',
                        fieldLabel: 'Automatic compliance check (hours)', inputType: OptionType.InputType.TEXT, defaultValue: '24', displayOrder: 39,
                        helpText: 'Runs the read-only host compliance check on every linked cluster this often and raises a Morpheus alarm on each host that fails it (cleared once it passes). 0 turns it off.'),
                new OptionType(name: 'Array tag namespace', code: 'pure.tagNamespace', fieldName: 'tagNamespace',
                        fieldLabel: 'Array tag namespace', inputType: OptionType.InputType.TEXT, defaultValue: PureNames.DEFAULT_TAG_NAMESPACE, displayOrder: 39,
                        helpText: 'Namespace of the tags the plugin writes on FlashArray volumes to recognise its datastores and recovery clones. Only volumes tagged in this namespace are managed. Keep the default for a new install; an install upgraded from an earlier build must keep the namespace its volumes already carry.'),
                new OptionType(name: 'Enable experimental operations', code: 'pure.enableExperimental', fieldName: 'enableExperimental',
                        fieldLabel: 'Enable experimental operations', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 40,
                        helpText: 'Clone a snapshot as a new datastore. Leave off until validated in a test environment.'),
        ]

        registerProvider(new PureStorageProvider(this, morpheus))
        registerProvider(new PureClusterTabProvider(this, morpheus))
        registerProvider(new PureInstanceTabProvider(this, morpheus))
        PureController controller = new PureController(this, morpheus)
        registerProvider(controller)
        controllers.add(controller)
        try {
            readSettings(morpheus, this)   // sets the tag namespace before any event reaches a provider
        } catch (Exception ignored) {
            // settings not readable yet; the sweeper and every request read them again
        }
        RecoverySessions.startSweeper(morpheus, this)
    }

    @Override
    void onDestroy() {
        RecoverySessions.stopSweeper()
    }

    /** The plugin settings. Every caller goes through here, so the tag namespace follows a settings change. */
    static Map readSettings(MorpheusContext morpheus, Plugin plugin) {
        String json = morpheus.getSettings(plugin).blockingGet()
        Map settings = json ? (new JsonSlurper().parseText(json) as Map) : [:]
        PureNames.configureTagNamespace(settings.tagNamespace as String)
        settings
    }
}
