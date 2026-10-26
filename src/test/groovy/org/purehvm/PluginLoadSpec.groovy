package org.purehvm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import spock.lang.Specification

import java.util.jar.JarInputStream

/**
 * Reproduces what the appliance's PluginManager.registerPlugin(jar) does: read Plugin-Class from the jar manifest,
 * load it, instantiate, set the context, call initialize(). Catches "Failed to parse plugin file" causes locally.
 */
class PluginLoadSpec extends Specification {

    def 'plugin initializes the way PluginManager loads it'() {
        given:
        Plugin plugin = new PureFlashArrayPlugin()
        Plugin.getDeclaredMethod('setMorpheus', MorpheusContext).with { accessible = true; invoke(plugin, Mock(MorpheusContext)) }

        when:
        plugin.initialize()

        then:
        noExceptionThrown()
        plugin.code == 'pure-flasharray-hvm'
        plugin.providers*.code.containsAll(['pure-flasharray-hvm.storage', 'pure-flasharray-hvm-clusterTab', 'pure-flasharray-hvm.controller'])
        // tab codes become href="#<code>" + a Bootstrap selector on the cluster page: no '.', ':' or spaces
        plugin.providers.findAll { it instanceof com.morpheusdata.core.providers.ClusterTabProvider }*.code.every { it ==~ /[A-Za-z][A-Za-z0-9_-]*/ }
        plugin.providers.find { it instanceof com.morpheusdata.core.providers.InstanceTabProvider }.code ==~ /[A-Za-z][A-Za-z0-9_-]*/
        plugin.controllers.size() == 1
        // controllers without a custom renderer are rejected by the 9.1.0.1 upload parser (verified on 9.1.0.1)
        plugin.hasCustomRender()
        plugin.settings.size() == 12
        plugin.settings.find { it.fieldName == 'tagNamespace' }.defaultValue == 'org.purehvm'
        plugin.permissions*.code as Set == ['pure-flasharray-ops', 'pure-flasharray-restore', 'pure-flasharray-admin'] as Set
        plugin.settings.find { it.fieldName == 'complianceCheckHours' }.defaultValue == '24'
        plugin.settings.find { it.fieldName == 'recoveryTimeoutHours' }.defaultValue == '8'
        plugin.settings.find { it.fieldName == 'fileRestoreHours' }.defaultValue == '24'
        plugin.settings.find { it.fieldName == 'fileRestoreMaxHours' }.defaultValue == '72'
        // the storage provider subscribes to host add/remove on clusters
        plugin.providers.find { it.code == 'pure-flasharray-hvm.storage' }.supportedEventTypes*.toString().containsAll(['ADD_WORKER', 'REMOVE_WORKER'])
    }

    def 'built jar (if present) has the manifest PluginManager reads and loads in a fresh classloader'() {
        given:
        String dir = System.getenv('PLUGIN_BUILD_DIR') ?: 'build'
        // the newest one: a build directory outside the project keeps every earlier version's jar
        File jar = new File(dir, 'libs').listFiles()?.findAll { it.name.endsWith('-all.jar') }?.max { it.lastModified() }
        if (!jar) {
            return   // shadowJar not built in this run
        }

        when:
        def mf = new JarInputStream(new FileInputStream(jar)).manifest
        String cls = mf.mainAttributes.getValue('Plugin-Class')
        def loader = new URLClassLoader([jar.toURI().toURL()] as URL[], Plugin.classLoader)
        def plugin = loader.loadClass(cls).getDeclaredConstructor().newInstance()

        then:
        cls == 'org.purehvm.PureFlashArrayPlugin'
        mf.mainAttributes.getValue('Plugin-Version')
        plugin instanceof Plugin
    }
}
