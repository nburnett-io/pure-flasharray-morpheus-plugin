package org.purehvm.morpheus

import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

/** Renders the real cluster-tab template with the same helpers AbstractClusterTabProvider registers. */
class ClusterTabTemplateSpec extends Specification {

    def 'tab renders with an external script asset, the request nonce and the React loader markers'() {
        given:
        def renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
        renderer.registerAssetHelper('Pure FlashArray for HVM')
        renderer.registerNonceHelper(Mock(MorpheusWebRequestService) { getNonceToken() >> 'n0nce' })
        def model = new ViewModel<Map>()
        model.object = [id: 15]

        when:
        String html = renderer.renderTemplate('hbs/pureClusterTab', model).html

        then:
        html.contains('data-cluster-id="15"')
        // versioned URL: Morpheus caches plugin assets for a year under a fixed name
        html =~ /<script src="\/assets\/plugin\/pure-flasharray-for-hvm\/pure-cluster-tab\.js\?v=\d+\.\d+\.\d+" nonce="n0nce"><\/script>/
        !html.contains('@PLUGIN_VERSION@')
        html.contains('data-plugin-react-root')
        html.contains('data-plugin-key="pureFlashArrayHvm"')
        !(html =~ /<script(?![^>]*\bsrc=)/)   // no inline scripts: Morpheus would never execute them
    }

    def 'instance Restore tab renders the same way, keyed by instance id'() {
        given:
        def renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
        renderer.registerAssetHelper('Pure FlashArray for HVM')
        renderer.registerNonceHelper(Mock(MorpheusWebRequestService) { getNonceToken() >> 'n0nce' })
        def model = new ViewModel<Map>()
        model.object = [id: 6]

        when:
        String html = renderer.renderTemplate('hbs/pureInstanceTab', model).html

        then:
        html.contains('data-instance-id="6"')
        html =~ /<script src="\/assets\/plugin\/pure-flasharray-for-hvm\/pure-instance-tab\.js\?v=\d+\.\d+\.\d+" nonce="n0nce"><\/script>/
        html.contains('data-plugin-entry="instanceTab"')
        !(html =~ /<script(?![^>]*\bsrc=)/)
    }
}
