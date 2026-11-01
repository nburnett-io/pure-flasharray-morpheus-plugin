package org.purehvm.morpheus

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import org.purehvm.PureFlashArrayPlugin
import groovy.util.logging.Slf4j

/** "Pure FlashArray" tab on HVM cluster pages. All data loads client-side from /plugin/pure-hvm/*. */
@Slf4j
class PureClusterTabProvider extends AbstractClusterTabProvider {
    final Plugin plugin
    final MorpheusContext morpheus

    PureClusterTabProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    /**
     * Must be a valid CSS id: Morpheus renders the tab as href="#<code>" and Bootstrap selects the pane with it,
     * so a '.' (read as a class selector) leaves the tab permanently blank.
     */
    static final String CODE = 'pure-flasharray-hvm-clusterTab'

    @Override String getCode() { CODE }
    @Override String getName() { 'Pure FlashArray' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        ViewModel<ComputeServerGroup> model = new ViewModel<>()
        model.object = cluster
        getRenderer().renderTemplate('hbs/pureClusterTab', model)
    }

    /** An HVM (MVM/KVM) type code, or none recorded. */
    static boolean hvmType(String code) {
        !code || code.toLowerCase() ==~ /.*(mvm|hvm|kvm).*/
    }

    /**
     * HVM clusters, and only for operators (master tenant, the cluster's owner, or the "Operator tenants" setting).
     * The cluster and user Morpheus passes in can be partial records (no owner, no account): load what is missing,
     * otherwise the owning tenant is taken for a customer and never sees the tab.
     */
    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        if (!hvmType(cluster?.type?.code)) {
            return false
        }
        Set<Long> extra = [] as Set<Long>
        try {
            extra = Tenancy.operatorTenantIds(PureFlashArrayPlugin.readSettings(morpheus, plugin))
        } catch (Exception ignored) {
            // settings unreadable: master tenant and cluster owner still qualify
        }
        ComputeServerGroup full = cluster?.owner == null && cluster?.id ? (morpheus.services.cluster.get(cluster.id) ?: cluster) : cluster
        User who = user?.account != null || account == null ? user : new User(id: user?.id, username: user?.username, account: account)
        boolean ok = Tenancy.isOperator(who, full, extra)
        if (!ok) {
            log.debug("cluster tab hidden on ${full?.name}: user ${who?.username} account ${who?.account?.id}, owner ${full?.owner?.id}")
        }
        ok
    }
}
