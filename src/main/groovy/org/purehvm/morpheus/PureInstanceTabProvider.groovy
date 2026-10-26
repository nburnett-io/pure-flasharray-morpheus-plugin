package org.purehvm.morpheus

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

/**
 * "Restore" tab on an HVM instance page: self-service restore of that VM from FlashArray snapshots (overwrite, file
 * recovery, add as a new disk, clone to a new VM). For customers as well as operators; every action re-checks that the VM is the caller's own
 * (or that they operate its cluster). Data loads client-side from /plugin/pure-hvm/vm-*.
 */
class PureInstanceTabProvider extends AbstractInstanceTabProvider {
    static final String CODE = 'pure-flasharray-hvm-instanceTab'   // a valid CSS id (no dots), as for the cluster tab

    final Plugin plugin
    final MorpheusContext morpheus

    PureInstanceTabProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { CODE }
    @Override String getName() { 'Restore' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        ViewModel<Instance> model = new ViewModel<>()
        model.object = instance
        getRenderer().renderTemplate('hbs/pureInstanceTab', model)
    }

    /** HVM instances, for users holding "Pure FlashArray VM Restore" (whether the VM is on Pure storage shows in the tab). */
    @Override
    Boolean show(Instance instance, User user, Account account) {
        if (!PureClusterTabProvider.hvmType(instance?.provisionType)) {
            return false
        }
        String access = user?.permissions?.get(PureController.PERM_RESTORE)
        access == null || access in ['read', 'full']
    }
}
