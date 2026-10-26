package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.User
import org.purehvm.PureFlashArrayPlugin
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * Exercises controller paths that only resolve at runtime (dynamic Groovy). 0.1.5 shipped without the
 * PureFlashArrayPlugin import and every tab call failed with MissingPropertyException on the appliance.
 */
class PureControllerSpec extends Specification {

    def 'settings are read through the plugin class'() {
        given:
        def morpheus = Mock(MorpheusContext) {
            getSettings(_) >> Single.just('{"enableExperimental":"on","registrationMode":"in-process"}')
        }
        def controller = new PureController(new PureFlashArrayPlugin(), morpheus)

        expect:
        controller.experimental()
        controller.registrar() instanceof InProcessGfs2Registrar
    }

    def 'in-process registration is the default (works for clusters in any tenant)'() {
        given:
        def morpheus = Mock(MorpheusContext) { getSettings(_) >> Single.just('{}') }

        expect:
        new PureController(new PureFlashArrayPlugin(), morpheus).registrar() instanceof InProcessGfs2Registrar
    }

    def 'REST registration, when chosen, needs the appliance URL and token'() {
        given:
        def morpheus = Mock(MorpheusContext) { getSettings(_) >> Single.just('{"registrationMode":"rest"}') }

        when:
        new PureController(new PureFlashArrayPlugin(), morpheus).registrar()

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('https://')
    }

    def 'every route points at a method that exists'() {
        given:
        def controller = new PureController(new PureFlashArrayPlugin(), Mock(MorpheusContext))

        expect:
        controller.routes.every { r -> PureController.methods.any { it.name == r.method && it.parameterCount == 1 } }
    }

    def 'changing the storage needs Administration; snapshot recovery and viewing do not'() {
        given:
        def routes = new PureController(new PureFlashArrayPlugin(), Mock(MorpheusContext)).routes

        expect:
        PureController.adminMethods(routes) == ['link', 'unlink', 'hostGroupApply', 'remediate', 'datastoreCreate', 'datastoreGrow', 'cloneDatastore',
                                                'hostAdd', 'hostMount', 'hostRescan', 'adopt', 'arrayHostRemove', 'iqnSync', 'retireFinish',
                                                'retireMorpheus', 'datastoreShare', 'reclaimDelete'] as Set
        // engineers (Operations: full) keep every snapshot restore
        routes.findAll { it.method in ['snapshotCreate', 'recoveryOpen', 'recoveryRestore', 'recoveryRestoreVm', 'recoveryClose', 'recoveryExtend'] }
                .every { r -> r.permissions*.code == [PureController.PERM] && r.permissions.collect { it.availableAccessTypes*.toString() } == [['full']] }
        routes.findAll { it.method.startsWith('vm') }.every { r -> r.permissions*.code == [PureController.PERM_RESTORE] }
        routes.findAll { it.method in ['snapshotPolicies', 'reclaimScan', 'compliance', 'status'] }.every { r -> r.permissions.collect { it.availableAccessTypes*.toString() } == [['read']] }
    }

    def 'the tab hides storage changes only when it knows the user lacks Administration'() {
        expect:
        PureController.canAdmin(new User(permissions: ['pure-flasharray-admin': 'full']))
        PureController.canAdmin(new User(permissions: ['pure-flasharray-ops': 'full'])) == false
        PureController.canAdmin(new User()) == null
    }
}
