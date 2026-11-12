package org.purehvm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import org.purehvm.api.PureNames
import org.purehvm.compliance.ComplianceChecker
import org.purehvm.morpheus.JobHistory
import org.purehvm.morpheus.Jobs
import org.purehvm.morpheus.PureClusterTabProvider
import org.purehvm.morpheus.PureController
import org.purehvm.morpheus.PureInstanceTabProvider
import org.purehvm.morpheus.PureSnapshotTaskProvider
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
                new OptionType(name: 'Multipath no_path_retry', code: 'pure.multipathNoPathRetry', fieldName: 'multipathNoPathRetry',
                        fieldLabel: 'Multipath no_path_retry', inputType: OptionType.InputType.TEXT, defaultValue: '0', displayOrder: 39,
                        helpText: 'What Remediate writes to the multipath drop-in: 0 (default, Pure\'s written value) or 1-60 retries. Changing this alters no host until someone runs Remediate on it. ' +
                                'With 0, I/O fails about 20 s after all paths go, and on GFS2 that withdraws the filesystem and the host is reset about 2 minutes later (seen on a test host with a 40 s path drop). A number holds I/O for about 20 s + N x polling_interval first, so a short outage passes (12 held the same 40 s drop with no error), but guests hang for that long, and past HVM\'s own clocks (all-paths-down at about 2 minutes, host offline at 140 s) the effects are unknown. ' +
                                'Nothing has been tested against a real controller failover. Pure and HPE have not said what GFS2 should use; this default follows their answer. ' +
                                'Remediate never takes a host\'s finite value back to 0 (unless the next setting allows it); anything other than 0 or 1-60 here counts as 0. Files in conf.d load after /etc/multipath.conf, so the drop-in beats a device value set with hvmcli.'),
                new OptionType(name: 'Multipath: allow lowering to 0', code: 'pure.multipathAllowZero', fieldName: 'multipathAllowZero',
                        fieldLabel: 'Multipath: allow Remediate to lower no_path_retry to 0', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 39,
                        helpText: 'Off (default): a host with a finite no_path_retry keeps it when the setting above is 0. On: the compliance check offers Remediate to write 0 on such hosts too, for when the vendors answer that GFS2 should fail I/O at once. Changes no host until someone runs Remediate.'),
                new OptionType(name: 'iSCSI: remove cross-fabric and routed sessions', code: 'pure.autoRemoveCrossFabric', fieldName: 'autoRemoveCrossFabric',
                        fieldLabel: 'iSCSI: remove cross-fabric and routed sessions automatically', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'on', displayOrder: 39,
                        helpText: 'On (default): every 10 minutes, a FlashArray iSCSI session that reaches its portal through a gateway, or through the other fabric\'s NIC (VLAN A portal over the VLAN B NIC), is logged out and its record deleted. ' +
                                'Only while the same portal has a correct session (or, for a routed one, two correct sessions exist), every LUN has at least 2 active paths, and no LUN is left without a path afterwards. ' +
                                'Off: such sessions only raise a warning alarm on the host, and Remediate removes them.'),
                new OptionType(name: 'Multipath tolerance warning (seconds)', code: 'pure.multipathToleranceWarnSeconds', fieldName: 'multipathToleranceWarnSeconds',
                        fieldLabel: 'Multipath tolerance warning (seconds)', inputType: OptionType.InputType.TEXT, defaultValue: String.valueOf(ComplianceChecker.DEFAULT_TOLERANCE_WARN_SECONDS), displayOrder: 39,
                        helpText: 'The compliance check warns when a host\'s finite no_path_retry would hold I/O longer than this (about 20 s + N x polling_interval). The default stays inside HVM\'s own storage-loss clocks (all-paths-down at about 2 minutes, host offline at 140 s).'),
                new OptionType(name: 'Array tag namespace', code: 'pure.tagNamespace', fieldName: 'tagNamespace',
                        fieldLabel: 'Array tag namespace', inputType: OptionType.InputType.TEXT, defaultValue: PureNames.DEFAULT_TAG_NAMESPACE, displayOrder: 39,
                        helpText: 'Namespace of the tags the plugin writes on FlashArray volumes to recognise its datastores and recovery clones. Only volumes tagged in this namespace are managed. Keep the default for a new install; an install upgraded from an earlier build must keep the namespace its volumes already carry.'),
                new OptionType(name: 'Application-consistent snapshots', code: 'pure.enableGuestFreeze', fieldName: 'enableGuestFreeze',
                        fieldLabel: 'Allow application-consistent snapshots (freeze guests)', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 39,
                        helpText: 'Off (default): snapshots are crash-consistent only. On: the cluster tab and the "Pure FlashArray snapshot" workflow task can freeze the file systems of every running VM on a datastore (qemu guest agent) for the moment of a snapshot. Guests pause writes for a few seconds; a host timer thaws each one after 60 s whatever happens.'),
                new OptionType(name: 'Enable experimental operations', code: 'pure.enableExperimental', fieldName: 'enableExperimental',
                        fieldLabel: 'Enable experimental operations', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 40,
                        helpText: 'Clone a snapshot as a new datastore. Leave off until validated in a test environment.'),
        ]

        registerProvider(new PureStorageProvider(this, morpheus))
        registerProvider(new PureClusterTabProvider(this, morpheus))
        registerProvider(new PureInstanceTabProvider(this, morpheus))
        registerProvider(new PureSnapshotTaskProvider(this, morpheus))
        PureController controller = new PureController(this, morpheus)
        registerProvider(controller)
        controllers.add(controller)
        try {
            readSettings(morpheus, this)   // sets the tag namespace before any event reaches a provider
        } catch (Exception ignored) {
            // settings not readable yet; the sweeper and every request read them again
        }
        RecoverySessions.startSweeper(morpheus, this)
        MorpheusContext ctx = morpheus
        Jobs.onFinish = { Jobs.Job j -> JobHistory.record(ctx, j) }
    }

    @Override
    void onDestroy() {
        Jobs.onFinish = null
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
