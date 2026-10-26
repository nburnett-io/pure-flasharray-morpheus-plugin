package org.purehvm.api

import groovy.json.JsonOutput
import spock.lang.Specification

class FlashArrayClientSpec extends Specification {

    /** Scripted transport: matches "METHOD path-prefix" and records every request. */
    static class FakeTransport implements HttpTransport {
        List<Map> calls = []
        List<Closure> handlers = []

        void on(String method, String pathContains, Closure<HttpTransport.Response> h) {
            handlers << { String m, URI u, Map hdr, String b -> (m == method && u.toString().contains(pathContains)) ? h(u, hdr, b) : null }
        }

        @Override
        HttpTransport.Response send(String method, URI uri, Map<String, String> headers, String body) {
            calls << [method: method, uri: uri.toString(), headers: headers, body: body]
            for (Closure c in handlers) {
                def r = c(method, uri, headers, body)
                if (r != null) {
                    return r
                }
            }
            new HttpTransport.Response(status: 404, body: '{"errors":[{"message":"no fake for ' + method + ' ' + uri + '"}]}')
        }
    }

    static HttpTransport.Response ok(Object body, Map headers = [:]) {
        new HttpTransport.Response(status: 200, body: JsonOutput.toJson(body), headers: headers.collectEntries { k, v -> [(k): [v]] })
    }

    def fake = new FakeTransport()
    def client = new FlashArrayClient('array.example.invalid', 'not-a-real-token', fake)

    def setup() {
        fake.on('GET', '/api/api_version') { u, h, b -> ok([version: ['1.19', '2.0', '2.3', '2.19', '2.36', '2.51', '2.60']]) }
        fake.on('POST', '/login') { u, h, b -> ok([:], ['x-auth-token': 'session-1']) }
        fake.on('POST', '/logout') { u, h, b -> ok([:]) }
    }

    def 'chooses the highest advertised 2.x up to MAX_API'() {
        expect:
        client.apiVersion == '2.51'
    }

    def 'rejects arrays without REST 2.3'() {
        expect:
        FlashArrayClient.chooseVersion(['2.4', '2.10']) == '2.10'

        when:
        FlashArrayClient.chooseVersion(['1.19', '2.0', '2.2'])

        then:
        thrown(FlashArrayException)
    }

    def 'url cleaning refuses http and adds https'() {
        expect:
        FlashArrayClient.cleanUrl(' array.example.invalid/ ') == 'https://array.example.invalid'

        when:
        FlashArrayClient.cleanUrl('http://array.example.invalid')

        then:
        thrown(IllegalArgumentException)
    }

    def 'logs in with api-token and sends x-auth-token on calls'() {
        given:
        fake.on('GET', '/api/2.51/arrays') { u, h, b -> ok([items: [[id: 'abc', name: 'fa1', version: '6.10.4']]]) }

        when:
        def a = client.array

        then:
        a.name == 'fa1'
        fake.calls.find { it.uri.endsWith('/login') }.headers['api-token'] == 'not-a-real-token'
        fake.calls.find { it.uri.contains('/arrays') }.headers['x-auth-token'] == 'session-1'
    }

    def 'follows continuation tokens'() {
        given:
        fake.on('GET', 'continuation_token=p2') { u, h, b -> ok([items: [[name: 'v3']], more_items_remaining: false]) }
        fake.on('GET', '/api/2.51/volumes') { u, h, b -> ok([items: [[name: 'v1'], [name: 'v2']], continuation_token: 'p2', more_items_remaining: true]) }

        expect:
        client.listVolumes()*.name == ['v1', 'v2', 'v3']
    }

    def 're-logs in once on 401'() {
        given:
        int n = 0
        fake.on('GET', '/api/2.51/hosts') { u, h, b ->
            n++ == 0 ? new HttpTransport.Response(status: 401, body: '') : ok([items: [[name: 'h1']]])
        }

        expect:
        client.listHosts()*.name == ['h1']
        fake.calls.count { it.uri.endsWith('/login') } == 2
    }

    def 'array error body becomes FlashArrayException with already-exists detection'() {
        given:
        fake.on('POST', '/api/2.51/host-groups') { u, h, b ->
            new HttpTransport.Response(status: 400, body: '{"errors":[{"context":"hg1","message":"Host group already exists."}]}')
        }

        when:
        client.createHostGroup('hg1')

        then:
        def e = thrown(FlashArrayException)
        e.alreadyExists
        e.message.contains('hg1: Host group already exists.')
    }

    def 'connect volume uses host group and never passes a LUN'() {
        given:
        fake.on('POST', '/api/2.51/connections') { u, h, b -> ok([items: [[lun: 1]]]) }

        when:
        client.connectVolumeToHostGroup('ds01', 'hvm-a')

        then:
        def c = fake.calls.find { it.uri.contains('/connections') }
        c.uri.contains('volume_names=ds01')
        c.uri.contains('host_group_names=hvm-a')
        !c.uri.contains('lun')
    }

    def 'copy from snapshot defaults to overwrite=false'() {
        given:
        fake.on('POST', '/api/2.51/volumes') { u, h, b -> ok([items: [[name: 'ds01-r1', serial: 'A1B2C3D4E5F6A7B8C9D0E1F2']]]) }

        when:
        def v = client.copyVolume('ds01.snap1', 'ds01-r1')

        then:
        v.serial == 'A1B2C3D4E5F6A7B8C9D0E1F2'
        def c = fake.calls.find { it.method == 'POST' && it.uri.contains('/volumes') }
        c.uri.contains('overwrite=false')
        c.body.contains('"source":{"name":"ds01.snap1"}')
    }

    static final String FAKE_TOKEN = '00000000-1111-2222-3333-444444444444'

    /** Transport without the shared token-login fixture, advertising REST 2.51. */
    static FakeTransport credentialArray() {
        def t = new FakeTransport()
        t.on('GET', '/api/api_version') { u, h, b -> ok([version: ['1.19', '2.48', '2.51']]) }
        t
    }

    def 'credentials -> new API token via REST 2.48, then logout'() {
        given:
        def fake = credentialArray()
        fake.on('POST', '/api/2.48/login') { u, h, b -> ok([items: []], ['x-auth-token': 'pw-session']) }
        fake.on('POST', '/api/2.48/admins/api-tokens') { u, h, b -> ok([items: [[name: 'svc', api_token: [token: FAKE_TOKEN]]]]) }
        fake.on('POST', '/api/2.48/logout') { u, h, b -> ok([:]) }

        when:
        def t = FlashArrayClient.exchangeCredentialsForApiToken('array.example.invalid', 'svc', 'not-a-real-password', fake)

        then:
        t == FAKE_TOKEN
        fake.calls.find { it.uri.endsWith('/api/2.48/login') }.body.contains('"username":"svc"')
        fake.calls.find { it.uri.contains('admins/api-tokens') }.headers['x-auth-token'] == 'pw-session'
        fake.calls.any { it.uri.endsWith('/api/2.48/logout') }
    }

    def 'credentials -> existing API token is exposed when the user already has one'() {
        given:
        def fake = credentialArray()
        fake.on('POST', '/api/2.48/login') { u, h, b -> ok([items: []], ['x-auth-token': 'pw-session']) }
        fake.on('POST', '/api/2.48/admins/api-tokens') { u, h, b -> new HttpTransport.Response(status: 400, body: '{"errors":[{"message":"API token already exists."}]}') }
        fake.on('GET', 'expose_api_token=true') { u, h, b -> ok([items: [[name: 'svc', api_token: [token: FAKE_TOKEN]]]]) }

        expect:
        FlashArrayClient.exchangeCredentialsForApiToken('array.example.invalid', 'svc', 'not-a-real-password', fake) == FAKE_TOKEN
    }

    def 'wrong password is reported plainly'() {
        given:
        def fake = credentialArray()
        fake.on('POST', '/api/2.48/login') { u, h, b -> new HttpTransport.Response(status: 401, body: '') }

        when:
        FlashArrayClient.exchangeCredentialsForApiToken('array.example.invalid', 'svc', 'wrong', fake)

        then:
        def e = thrown(FlashArrayException)
        e.message.contains('rejected the username or password')
    }

    def 'older arrays use REST 1.x auth/apitoken'() {
        given:
        def old = new FakeTransport()
        old.on('GET', '/api/api_version') { u, h, b -> ok([version: ['1.17', '1.19', '2.0', '2.36']]) }
        old.on('POST', '/api/1.19/auth/apitoken') { u, h, b -> ok([api_token: FAKE_TOKEN]) }

        expect:
        FlashArrayClient.exchangeCredentialsForApiToken('array.example.invalid', 'svc', 'not-a-real-password', old) == FAKE_TOKEN
    }

    /** Array that tracks created admins; logins succeed for 'admin' and for any created user with its password. */
    static FakeTransport userArray(Map<String, Map> admins, Closure<HttpTransport.Response> onCreate = null, boolean tokenWorks = true) {
        def t = credentialArray()
        Map<String, String> passwords = [:]
        t.on('POST', '/api/2.48/login') { u, h, b ->
            def j = new groovy.json.JsonSlurper().parseText(b)
            boolean valid = (j.username == 'admin' && j.password == 'not-a-real-admin-pw') || (passwords[j.username] && passwords[j.username] == j.password)
            valid ? ok([items: [[username: j.username]]], ['x-auth-token': "s-${j.username}".toString()]) : new HttpTransport.Response(status: 401, body: '')
        }
        t.on('GET', '/api/2.48/admins?names=') { u, h, b ->
            String n = URLDecoder.decode(u.query.replaceFirst('.*names=', '').replaceFirst('&.*', ''), 'UTF-8')
            ok([items: admins[n] ? [admins[n]] : []])
        }
        t.on('POST', '/api/2.48/admins?names=') { u, h, b ->
            if (onCreate) { def r = onCreate(u, h, b); if (r) return r }
            def j = new groovy.json.JsonSlurper().parseText(b)
            String n = u.query.replaceFirst('.*names=', '')
            passwords[n] = j.password
            admins[n] = [name: n, role: j.role ?: [name: j.management_access_policies[0].name]]
            ok([items: [admins[n]]])
        }
        t.on('DELETE', '/api/2.48/admins?names=') { u, h, b -> admins.remove(u.query.replaceFirst('.*names=', '')); ok([:]) }
        t.on('POST', '/api/2.48/admins/api-tokens') { u, h, b ->
            tokenWorks ? ok([items: [[api_token: [token: FAKE_TOKEN]]]]) : new HttpTransport.Response(status: 500, body: '')
        }
        t.on('GET', 'expose_api_token=true') { u, h, b -> new HttpTransport.Response(status: 500, body: '') }
        t.on('POST', '/api/2.48/logout') { u, h, b -> ok([:]) }
        t
    }

    def 'bootstrap creates a storage_admin user with a generated password and returns ITS token'() {
        given:
        def admins = [admin: [name: 'admin', role: [name: 'array_admin']]]
        def t = userArray(admins)

        when:
        def tok = FlashArrayClient.bootstrapServiceUser('array.example.invalid', 'admin', 'not-a-real-admin-pw', 'morpheus-hvm', t)

        then:
        tok == FAKE_TOKEN
        admins['morpheus-hvm'].role.name == 'storage_admin'
        def create = t.calls.find { it.method == 'POST' && it.uri.contains('/admins?names=morpheus-hvm') }
        def pw = new groovy.json.JsonSlurper().parseText(create.body).password
        pw.length() == 32
        // the token was requested in the NEW user's session, not the admin's
        t.calls.find { it.uri.contains('admins/api-tokens') }.headers['x-auth-token'] == 's-morpheus-hvm'
    }

    def 'bootstrap falls back to management access policies'() {
        given:
        def admins = [admin: [name: 'admin', role: [name: 'array_admin']]]
        def t = userArray(admins, { u, h, b -> b.contains('"role"') ? new HttpTransport.Response(status: 400, body: '{"errors":[{"message":"role is deprecated"}]}') : null })

        expect:
        FlashArrayClient.bootstrapServiceUser('array.example.invalid', 'admin', 'not-a-real-admin-pw', 'morpheus-hvm', t) == FAKE_TOKEN
        admins['morpheus-hvm'].role.name == 'storage_admin'
    }

    def 'bootstrap refuses an existing user and a non-admin login'() {
        when:
        FlashArrayClient.bootstrapServiceUser('array.example.invalid', 'admin', 'not-a-real-admin-pw', 'taken',
                userArray([admin: [name: 'admin', role: [name: 'array_admin']], taken: [name: 'taken', role: [name: 'storage_admin']]]))

        then:
        def e = thrown(FlashArrayException)
        e.message.contains('already exists')

        when:
        FlashArrayClient.bootstrapServiceUser('array.example.invalid', 'admin', 'not-a-real-admin-pw', 'morpheus-hvm',
                userArray([admin: [name: 'admin', role: [name: 'storage_admin']]]))

        then:
        def e2 = thrown(FlashArrayException)
        e2.message.contains('needs an array_admin login')
    }

    def 'bootstrap deletes the new user again if its token cannot be issued'() {
        given:
        def admins = [admin: [name: 'admin', role: [name: 'array_admin']]]
        def t = userArray(admins, null, false)

        when:
        FlashArrayClient.bootstrapServiceUser('array.example.invalid', 'admin', 'not-a-real-admin-pw', 'morpheus-hvm', t)

        then:
        thrown(FlashArrayException)
        !admins.containsKey('morpheus-hvm')
        t.calls.any { it.method == 'DELETE' && it.uri.contains('admins?names=morpheus-hvm') }
    }

    def 'refused role is detected BEFORE any token is created'() {
        given:
        def t = userArray([admin: [name: 'admin', role: [name: 'array_admin']]])

        when:
        FlashArrayClient.exchangeCredentialsForApiToken('array.example.invalid', 'admin', 'not-a-real-admin-pw', t, ['array_admin'])

        then:
        def e = thrown(FlashArrayException.RoleRefusedException)
        e.role == 'array_admin'
        !t.calls.any { it.uri.contains('admins/api-tokens') }
    }

    def 'role read from either field'() {
        expect:
        FlashArrayClient.roleOf([role: [name: 'storage_admin']]) == 'storage_admin'
        FlashArrayClient.roleOf([management_access_policies: [[name: 'array_admin']]]) == 'array_admin'
        FlashArrayClient.randomPassword() ==~ /(?=.*[A-Z])(?=.*[a-z])(?=.*\d)(?=.*[!#%+=?@^_]).{32}/
    }

    def 'token vs password detection'() {
        expect:
        FlashArrayClient.looksLikeApiToken(FAKE_TOKEN)
        !FlashArrayClient.looksLikeApiToken('Summer2026!')
        !FlashArrayClient.looksLikeApiToken(null)
    }

    def 'iSCSI portals are enabled eth interfaces with the iscsi service'() {
        given:
        fake.on('GET', '/network-interfaces') { u, h, b ->
            ok([items: [
                    [name: 'ct0.eth4', enabled: true, services: ['iscsi'], interface_type: 'eth', eth: [address: '192.0.2.10', mtu: 9000]],
                    [name: 'ct0.eth0', enabled: true, services: ['management'], interface_type: 'eth', eth: [address: '192.0.2.200']],
                    [name: 'ct1.eth4', enabled: false, services: ['iscsi'], interface_type: 'eth', eth: [address: '192.0.2.11']],
            ]])
        }

        expect:
        client.listIscsiPortals()*.address == ['192.0.2.10']
    }
}
