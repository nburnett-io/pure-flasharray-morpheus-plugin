package org.purehvm.morpheus

import org.purehvm.api.FlashArrayException
import org.purehvm.api.HttpTransport
import org.purehvm.ops.Gfs2Registrar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Creates the GFS2 datastore through Morpheus' documented REST API, exactly as the UI's
 * "Add Data Store > HPE Clustered Datastore (Shared LUN)" does:
 *   POST /api/clusters/{id}/datastores {datastore:{name, datastoreType:{code}, config:{blockDevice, allowReformat}}}
 * The token belongs to a dedicated Morpheus service user and lives in the plugin's (masked) settings.
 */
class RestGfs2Registrar implements Gfs2Registrar {
    final String applianceUrl
    private final String token
    private final HttpTransport http

    RestGfs2Registrar(String applianceUrl, String token, HttpTransport http) {
        this.applianceUrl = (applianceUrl ?: '').replaceAll('/+$', '')
        this.token = token
        this.http = http
        if (!this.applianceUrl.startsWith('https://')) {
            throw new IllegalArgumentException('Morpheus appliance URL must be https://')
        }
        if (!token) {
            throw new IllegalArgumentException('A Morpheus API token is required for REST registration (plugin settings)')
        }
    }

    private Map call(String method, String path, Object body = null) {
        HttpTransport.Response r = http.send(method, URI.create(applianceUrl + path),
                ['Authorization': "Bearer ${token}".toString(), 'Accept': 'application/json', 'Content-Type': 'application/json'],
                body == null ? null : JsonOutput.toJson(body))
        if (r.status < 200 || r.status >= 300) {
            String msg = r.body?.take(500)
            throw new FlashArrayException("Morpheus ${method} ${path}: HTTP ${r.status} ${msg}", r.status)
        }
        r.body ? new JsonSlurper().parseText(r.body) as Map : [:]
    }

    /** The GFS2 datastore type code is not documented; find it from GET /api/data-store-types (new in 9.1). */
    String gfs2TypeCode() {
        Map res = call('GET', '/api/data-store-types?max=200')
        List types = (res.values().find { it instanceof List } ?: []) as List
        Map t = types.find { Map m -> (m.code as String)?.toLowerCase()?.contains('gfs2') && m.creatable != false } as Map
        if (!t) {
            throw new IllegalStateException("No GFS2 datastore type found (types: ${types*.code.join(', ')})")
        }
        t.code
    }

    @Override
    Long register(Long clusterId, String name, String devicePath) {
        Map res
        try {
            res = call('POST', "/api/clusters/${clusterId}/datastores", [datastore: [
                    name           : name,
                    datastoreType  : [code: gfs2TypeCode()],
                    config         : [blockDevice: devicePath, allowReformat: false],
                    heartbeatTarget: false,
                    active         : true,
            ]])
        } catch (FlashArrayException e) {
            if (e.status == 404) {
                // seen on 9.1: GET works across tenants, POST looks the cluster up in the token's own tenant only
                throw new IllegalStateException("${e.message}. The plugin's Morpheus API token belongs to a different tenant than " +
                        "this cluster, and Morpheus only lets the owning tenant add datastores. Set the plugin's GFS2 " +
                        "registration to in-process (no token needed), or use a token from a user in the cluster's tenant.", e)
            }
            throw e
        }
        Object id = (res.datastore as Map)?.id ?: res.id
        if (!id) {
            throw new IllegalStateException("Morpheus did not return a datastore id: ${JsonOutput.toJson(res).take(300)}")
        }
        id as Long
    }
}
