package org.purehvm.api

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Purity//FA REST 2.x client covering what the HVM integration needs: hosts, host groups, volumes,
 * connections, snapshots and iSCSI portals.
 *
 * Behaviour, matched against Pure's own vSphere plugin and tightened where it was loose:
 *  - version: GET /api/api_version, use the highest 2.x the array advertises (capped at MAX_API);
 *    2.3 is the floor.
 *  - auth: POST /api/{v}/login with header api-token -> x-auth-token. Re-logs in once on 401 and
 *    logs out on close (Pure's plugin does neither and leaks sessions).
 *  - paging: follows continuation_token until more_items_remaining is false.
 *  - errors: the array's {"errors":[{"context","message"}]} body becomes a FlashArrayException.
 *
 * The API token is held in memory only; callers fetch it from the Morpheus credential store.
 */
@Slf4j
class FlashArrayClient implements Closeable {
    static final String MIN_API = '2.3'
    static final String MAX_API = '2.51'
    static final int PAGE_SIZE = 1000
    static final String USER_AGENT = 'morpheus-pure-hvm-plugin'

    final String baseUrl
    private final String apiToken
    private final HttpTransport transport
    private String version
    private String authToken
    private String sessionUser

    FlashArrayClient(String baseUrl, String apiToken, HttpTransport transport) {
        this.baseUrl = cleanUrl(baseUrl)
        this.apiToken = apiToken
        this.transport = transport
    }

    static FlashArrayClient connect(String url, String apiToken, String pinnedSha256) {
        new FlashArrayClient(url, apiToken, new JdkHttpTransport(pinnedSha256))
    }

    /** https:// only; bare hosts get the scheme added; trailing slashes removed. */
    static String cleanUrl(String url) {
        String u = (url ?: '').trim()
        if (!u) {
            throw new IllegalArgumentException('Array URL is required')
        }
        if (u.toLowerCase().startsWith('http://')) {
            throw new IllegalArgumentException('Array URL must use https')
        }
        if (!u.toLowerCase().startsWith('https://')) {
            u = 'https://' + u
        }
        u.replaceAll('/+$', '')
    }

    // ---------------------------------------------------------------- credentials -> API token

    /** Purity API tokens are UUIDs; anything else in the secret field is treated as a password. */
    static boolean looksLikeApiToken(String secret) {
        secret ==~ /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/
    }

    /**
     * Logs in with a username/password once and returns that user's API token, creating it if the user has none
     * (Pure's vSphere plugin does the same; the password is never stored).
     *  - REST >= 2.48: POST /api/2.48/login {username,password} -> x-auth-token; POST admins/api-tokens?names=<user>;
     *    if the user already has a token (HTTP 400), GET admins/api-tokens?names=<user>&expose_api_token=true.
     *  - older arrays: REST 1.x POST /api/1.<n>/auth/apitoken {username,password} -> api_token.
     */
    static String exchangeCredentialsForApiToken(String url, String username, String password, HttpTransport transport,
                                                 Collection<String> refuseRoles = []) {
        String base = cleanUrl(url)
        List<String> versions = advertisedVersions(base, transport, true)

        if (hasPasswordLogin(versions)) {
            Map<String, String> auth = passwordLogin(base, username, password, transport, 'The array rejected the username or password')
            String user = URLEncoder.encode(username, 'UTF-8')
            try {
                if (refuseRoles) {
                    // check BEFORE issuing a token, so a refused login leaves nothing behind on the array
                    String role = adminRole(base, auth, username, transport)
                    if (role in refuseRoles) {
                        throw new FlashArrayException.RoleRefusedException(username, role)
                    }
                }
                HttpTransport.Response c = transport.send('POST', URI.create("${base}/api/2.48/admins/api-tokens?names=${user}"), auth, '{}')
                if (c.status == 200) {
                    return tokenFrom(c.body)
                }
                HttpTransport.Response g = transport.send('GET', URI.create("${base}/api/2.48/admins/api-tokens?names=${user}&expose_api_token=true"), auth, null)
                if (g.status == 200) {
                    return tokenFrom(g.body)
                }
                throw new FlashArrayException("Could not create or read an API token for ${username}: HTTP ${c.status}/${g.status}", g.status, parseErrors(g.body ?: c.body))
            } finally {
                quietLogout(base, auth, transport)
            }
        }

        String v1 = versions.findAll { it ==~ /1\.\d+/ }.max { String a, String b -> compareVersions(a, b) }
        if (!v1) {
            throw new FlashArrayException('Array offers neither REST 2.48+ nor REST 1.x for token creation; create an API token on the array and enter it instead')
        }
        HttpTransport.Response t = transport.send('POST', URI.create("${base}/api/${v1}/auth/apitoken"), JSON_HEADERS,
                JsonOutput.toJson([username: username, password: password]))
        if (t.status != 200) {
            throw new FlashArrayException(t.status in [400, 401, 403] ? 'The array rejected the username or password' : "Token request failed: HTTP ${t.status}", t.status)
        }
        String token = (new JsonSlurper().parseText(t.body ?: '{}') as Map).api_token
        if (!token) {
            throw new FlashArrayException('The array returned no API token')
        }
        token
    }

    private static final Map<String, String> JSON_HEADERS = ['Accept': 'application/json', 'Content-Type': 'application/json', 'User-Agent': USER_AGENT]

    /** The REST versions the array advertises; [] when it does not answer 200, unless {@code required} (then it throws). */
    private static List<String> advertisedVersions(String base, HttpTransport transport, boolean required) {
        HttpTransport.Response v = transport.send('GET', URI.create("${base}/api/api_version"), ['Accept': 'application/json', 'User-Agent': USER_AGENT], null)
        if (v.status != 200) {
            if (required) {
                throw new FlashArrayException("GET /api/api_version returned HTTP ${v.status}", v.status)
            }
            return []
        }
        (new JsonSlurper().parseText(v.body ?: '{}') as Map).version as List<String> ?: []
    }

    /** Username/password login (and so token issuance) exists from REST 2.48. */
    private static boolean hasPasswordLogin(List<String> versions) {
        versions.any { it ==~ /2\.\d+/ && compareVersions(it, '2.48') >= 0 }
    }

    /** POST /api/2.48/login with a username and password; the session's request headers. */
    private static Map<String, String> passwordLogin(String base, String user, String password, HttpTransport transport, String rejected) {
        HttpTransport.Response l = transport.send('POST', URI.create("${base}/api/2.48/login"), JSON_HEADERS, JsonOutput.toJson([username: user, password: password]))
        if (l.status != 200 || !l.header('x-auth-token')) {
            throw new FlashArrayException(l.status in [400, 401, 403] ? rejected : "Login failed: HTTP ${l.status}", l.status, parseErrors(l.body))
        }
        JSON_HEADERS + ['x-auth-token': l.header('x-auth-token')]
    }

    /** An admin's effective role, or null when the session may not read it. */
    private static String adminRole(String base, Map<String, String> auth, String name, HttpTransport transport) {
        HttpTransport.Response me = transport.send('GET', URI.create("${base}/api/2.48/admins?names=${URLEncoder.encode(name, 'UTF-8')}"), auth, null)
        me.status == 200 ? roleOf(firstItem(me.body)) : null
    }

    private static void quietLogout(String base, Map<String, String> auth, HttpTransport transport) {
        try {
            transport.send('POST', URI.create("${base}/api/2.48/logout"), auth, '')
        } catch (Exception ignored) {
        }
    }

    private static Map firstItem(String body) {
        ((new JsonSlurper().parseText(body ?: '{}') as Map).items as List<Map>)?.find()
    }

    static final String SERVICE_ROLE = 'storage_admin'

    /** Effective role of an admin record: role.name, else the first management access policy name (Purity 6.8+). */
    static String roleOf(Map admin) {
        ((admin?.role as Map)?.name ?: ((admin?.management_access_policies as List<Map>)?.find()?.name)) as String
    }

    static String randomPassword() {
        String chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+=?@^_'
        java.security.SecureRandom r = new java.security.SecureRandom()
        // guarantee every class Purity password policies may require
        List<String> pw = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!#%+=?@^_'].collect { it[r.nextInt(it.length())] }
        (1..28).each { pw << chars[r.nextInt(chars.length())] }
        Collections.shuffle(pw, r)
        pw.join('')
    }

    /**
     * Creates a dedicated local service user with the storage_admin role, using an array_admin login, and returns
     * the NEW user's API token. The admin credentials and the generated password are used once and never stored.
     * Refuses if the user already exists (we cannot get an existing user's token without its password).
     * Requires REST 2.48+ (password login). If token issuance fails after the user was created, the user is deleted.
     */
    static String bootstrapServiceUser(String url, String adminUser, String adminPassword, String serviceUser, HttpTransport transport) {
        if (!(serviceUser ==~ /[A-Za-z][A-Za-z0-9_.-]{0,31}/)) {
            throw new IllegalArgumentException('Service user name: start with a letter; letters, digits, . _ - only; max 32 characters')
        }
        String base = cleanUrl(url)
        if (!hasPasswordLogin(advertisedVersions(base, transport, false))) {
            throw new FlashArrayException('Automatic service-user creation needs Purity REST 2.48 or later; create the storage_admin user on the array and enter its login instead')
        }
        Map<String, String> auth = passwordLogin(base, adminUser, adminPassword, transport, 'The array rejected the admin username or password')
        String enc = URLEncoder.encode(serviceUser, 'UTF-8')
        boolean created = false
        try {
            String myRole = adminRole(base, auth, adminUser, transport)
            if (myRole && myRole != 'array_admin') {
                throw new FlashArrayException("${adminUser} is ${myRole}; creating users needs an array_admin login")
            }
            HttpTransport.Response ex = transport.send('GET', URI.create("${base}/api/2.48/admins?names=${enc}"), auth, null)
            if (ex.status == 200 && ((new JsonSlurper().parseText(ex.body) as Map).items as List)) {
                throw new FlashArrayException("User ${serviceUser} already exists on the array; pick a new name, or enter that user's own login instead")
            }

            String pw = randomPassword()
            HttpTransport.Response c = transport.send('POST', URI.create("${base}/api/2.48/admins?names=${enc}"), auth,
                    JsonOutput.toJson([password: pw, role: [name: SERVICE_ROLE]]))
            if (c.status != 200) {
                // Purity builds that only accept management access policies
                c = transport.send('POST', URI.create("${base}/api/2.48/admins?names=${enc}"), auth,
                        JsonOutput.toJson([password: pw, management_access_policies: [[name: SERVICE_ROLE]]]))
            }
            if (c.status != 200) {
                List<Map> errs = parseErrors(c.body)
                throw new FlashArrayException("Could not create ${serviceUser}: ${errs ? errs*.message.join('; ') : 'HTTP ' + c.status}", c.status, errs)
            }
            created = true

            String token = exchangeCredentialsForApiToken(base, serviceUser, pw, transport)
            pw = null
            String role = adminRole(base, auth, serviceUser, transport)
            if (role && role != SERVICE_ROLE) {
                throw new FlashArrayException("${serviceUser} was created with role ${role}, not ${SERVICE_ROLE}")
            }
            created = false   // success: keep the user
            return token
        } finally {
            if (created) {
                try {
                    transport.send('DELETE', URI.create("${base}/api/2.48/admins?names=${enc}"), auth, null)
                } catch (Exception ignored) {
                }
            }
            quietLogout(base, auth, transport)
        }
    }

    /** Role of the user the current session belongs to (from the login response), or null if unknown. */
    String sessionRole() {
        if (!authToken) {
            login()
        }
        sessionUser ? roleOf(list('admins', [names: sessionUser]).find()) : null
    }

    private static String tokenFrom(String body) {
        String token = (firstItem(body)?.api_token as Map)?.token
        if (!token) {
            throw new FlashArrayException('The array returned no API token (the token may exist but is not exposed to this user)')
        }
        token
    }

    // ---------------------------------------------------------------- version + session

    static int compareVersions(String a, String b) {
        List<Integer> x = a.tokenize('.').collect { it as Integer }
        List<Integer> y = b.tokenize('.').collect { it as Integer }
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = (x[i] ?: 0) <=> (y[i] ?: 0)
            if (c != 0) {
                return c
            }
        }
        0
    }

    static String chooseVersion(List<String> versions) {
        List<String> twoX = versions.findAll { it ==~ /2\.\d+/ && compareVersions(it, MIN_API) >= 0 }
        if (!twoX) {
            throw new FlashArrayException("Array does not support REST API ${MIN_API} or later (advertises ${versions.join(', ') ?: 'nothing'})")
        }
        List<String> capped = twoX.findAll { compareVersions(it, MAX_API) <= 0 }
        (capped ?: twoX).max { String a, String b -> compareVersions(a, b) }
    }

    String getApiVersion() {
        ensureVersion()
        version
    }

    private void ensureVersion() {
        if (!version) {
            version = chooseVersion(advertisedVersions(baseUrl, transport, true))
        }
    }

    private void login() {
        ensureVersion()
        if (!apiToken) {
            throw new FlashArrayException('No API token configured for this array')
        }
        HttpTransport.Response r = transport.send('POST', URI.create("${baseUrl}/api/${version}/login"),
                ['api-token': apiToken, 'Accept': 'application/json', 'User-Agent': USER_AGENT], '')
        if (r.status != 200) {
            throw new FlashArrayException(r.status == 401 || r.status == 403 ? 'Array rejected the API token' : "Login failed: HTTP ${r.status}",
                    r.status, parseErrors(r.body))
        }
        authToken = r.header('x-auth-token')
        if (!authToken) {
            throw new FlashArrayException('Login succeeded but the array returned no x-auth-token')
        }
        try {
            sessionUser = firstItem(r.body)?.username
        } catch (Exception ignored) {
            sessionUser = null
        }
    }

    @Override
    void close() {
        if (authToken) {
            try {
                transport.send('POST', URI.create("${baseUrl}/api/${version}/logout"), ['x-auth-token': authToken, 'User-Agent': USER_AGENT], '')
            } catch (Exception e) {
                log.debug("FlashArray logout failed: ${e.message}")
            }
            authToken = null
        }
    }

    // ---------------------------------------------------------------- raw request helpers

    static String encodeQuery(Map query) {
        (query ?: [:]).findAll { k, v -> v != null }.collect { k, v ->
            String value = v instanceof Collection ? (v as Collection).join(',') : v.toString()
            "${URLEncoder.encode(k as String, 'UTF-8')}=${URLEncoder.encode(value, 'UTF-8').replace('+', '%20')}"
        }.join('&')
    }

    static List<Map> parseErrors(String body) {
        if (!body) {
            return []
        }
        try {
            Object j = new JsonSlurper().parseText(body)
            return j instanceof Map && j.errors instanceof List ? (j.errors as List<Map>) : []
        } catch (Exception ignored) {
            return []
        }
    }

    /** One call. Returns the parsed body (Map) or [:] for empty responses. */
    Map call(String method, String path, Map query = [:], Object body = null) {
        if (!authToken) {
            login()
        }
        HttpTransport.Response r = send(method, path, query, body)
        if (r.status == 401) {
            log.debug('FlashArray session expired; logging in again')
            login()
            r = send(method, path, query, body)
        }
        if (r.status < 200 || r.status >= 300) {
            List<Map> errs = parseErrors(r.body)
            String detail = errs ? errs.collect { e -> [e.context, e.message].findAll().join(': ') }.join('; ') : "HTTP ${r.status}"
            throw new FlashArrayException("${method} ${path} failed: ${detail}", r.status, errs)
        }
        r.body ? (new JsonSlurper().parseText(r.body) as Map) : [:]
    }

    private HttpTransport.Response send(String method, String path, Map query, Object body) {
        String qs = encodeQuery(query)
        URI uri = URI.create("${baseUrl}/api/${version}/${path}${qs ? '?' + qs : ''}")
        Map<String, String> headers = ['x-auth-token': authToken, 'Accept': 'application/json', 'User-Agent': USER_AGENT,
                                       'X-Request-ID': UUID.randomUUID().toString()]
        String payload = null
        if (body != null) {
            headers['Content-Type'] = 'application/json'
            payload = JsonOutput.toJson(body)
        }
        transport.send(method, uri, headers, payload)
    }

    /** GET every page of a collection endpoint. */
    List<Map> list(String path, Map query = [:]) {
        List<Map> items = []
        String token = null
        while (true) {
            Map q = new LinkedHashMap(query ?: [:])
            q.limit = PAGE_SIZE
            if (token) {
                q.continuation_token = token
            }
            Map page = call('GET', path, q)
            items.addAll((page.items ?: []) as List<Map>)
            token = page.continuation_token as String
            if (!token || page.more_items_remaining == false) {
                break
            }
        }
        items
    }

    // ---------------------------------------------------------------- array

    Map getArray() {
        list('arrays').find() ?: [:]
    }

    List<Map> listControllers() {
        list('controllers')
    }

    /** Enabled ethernet interfaces offering iSCSI, i.e. the portals hosts should log in to. */
    List<Map> listIscsiPortals() {
        list('network-interfaces').findAll { Map ni ->
            ni.enabled && (ni.services as List)?.contains('iscsi') && ni.interface_type == 'eth' && (ni.eth as Map)?.address
        }.collect { Map ni ->
            Map eth = ni.eth as Map
            [name: ni.name, address: eth.address, subnet: (eth.subnet as Map)?.name, netmask: eth.netmask, mtu: eth.mtu, vlan: eth.vlan]
        }
    }

    // ---------------------------------------------------------------- hosts / host groups

    List<Map> listHosts() {
        list('hosts', [filter: "is_local='true'"])
    }

    Map createHost(String name, List<String> iqns) {
        call('POST', 'hosts', [names: name], [iqns: iqns]).items?.find() as Map
    }

    void addIqnsToHost(String name, List<String> iqns) {
        call('PATCH', 'hosts', [names: name], [add_iqns: iqns])
    }

    void deleteHost(String name) {
        call('DELETE', 'hosts', [names: name])
    }

    List<Map> listHostGroups() {
        list('host-groups', [filter: "is_local='true'"])
    }

    Map createHostGroup(String name) {
        call('POST', 'host-groups', [names: name]).items?.find() as Map
    }

    /** [group: <hgroup name>, member: <host name>] pairs. */
    List<Map> listHostGroupMembers(List<String> groupNames = null) {
        list('host-groups/hosts', groupNames ? [group_names: groupNames] : [:]).collect { Map m ->
            [group: (m.group as Map)?.name, member: (m.member as Map)?.name]
        }
    }

    void addHostsToHostGroup(String group, List<String> hosts) {
        call('POST', 'hosts/host-groups', [group_names: group, member_names: hosts])
    }

    void removeHostFromHostGroup(String group, String host) {
        call('DELETE', 'hosts/host-groups', [group_names: group, member_names: host])
    }

    /** Purity refuses to delete a host group that still has members or volume connections. */
    void deleteHostGroup(String name) {
        call('DELETE', 'host-groups', [names: name])
    }

    // ---------------------------------------------------------------- volumes

    List<Map> listVolumes(Map query = [destroyed: false]) {
        list('volumes', query)
    }

    Map getVolume(String name) {
        list('volumes', [names: name]).find()
    }

    /** The live volume with this serial (the serial is validated before it goes into the filter), or null. */
    Map volumeBySerial(String serial) {
        listVolumes([filter: "serial='${PureNames.requireSerial(serial)}'".toString(), destroyed: false]).find()
    }

    /** True if the name is taken, including volumes still in the destroyed (eradication-pending) bucket. */
    boolean volumeNameTaken(String name) {
        String lower = name.toLowerCase()
        [true, false].any { Boolean destroyed ->
            list('volumes', [destroyed: destroyed]).any { (it.name as String)?.toLowerCase() == lower }
        }
    }

    Map createVolume(String name, long provisionedBytes) {
        call('POST', 'volumes', [names: name], [provisioned: provisionedBytes]).items?.find() as Map
    }

    /** New volume from a snapshot or volume; overwrite=false makes the array refuse to clobber an existing volume. */
    Map copyVolume(String sourceName, String newName) {
        call('POST', 'volumes', [names: newName, overwrite: false], [source: [name: sourceName]]).items?.find() as Map
    }

    /** Grow only: truncate=false makes the array refuse a shrink. */
    Map resizeVolume(String name, long provisionedBytes) {
        call('PATCH', 'volumes', [names: name, truncate: false], [provisioned: provisionedBytes]).items?.find() as Map
    }

    /** Moves the volume to the destroyed bucket (recoverable until the eradication delay passes). */
    void destroyVolume(String name) {
        call('PATCH', 'volumes', [names: name], [destroyed: true])
    }

    void eradicateVolume(String name) {
        call('DELETE', 'volumes', [names: name])
    }

    /** Tags are not copyable: a snapshot clone must never inherit its source's cluster/datastore tags. */
    void tagVolume(String name, String namespace, Map<String, String> tags) {
        call('PUT', 'volumes/tags/batch', [resource_names: name],
                tags.collect { k, v -> [namespace: namespace, key: k, value: v, copyable: false] })
    }

    List<Map> listVolumeTags(String namespace, List<String> volumeNames = null) {
        Map q = [namespaces: namespace]
        if (volumeNames) {
            q.resource_names = volumeNames
        }
        list('volumes/tags', q)
    }

    /** The plugin's tags of every volume (or of the given ones): volume name -> key -> value. */
    Map<String, Map<String, String>> tagsByVolume(List<String> volumeNames = null) {
        Map<String, Map<String, String>> byVol = [:]
        listVolumeTags(PureNames.tagNamespace, volumeNames).each { Map t ->
            byVol.computeIfAbsent((t.resource as Map)?.name as String) { [:] }[t.key as String] = t.value as String
        }
        byVol
    }

    /** The plugin's tags of one volume: key -> value. */
    Map<String, String> volumeTags(String volumeName) {
        listVolumeTags(PureNames.tagNamespace, [volumeName]).collectEntries { [(it.key as String): it.value as String] }
    }

    // ---------------------------------------------------------------- connections

    List<Map> listConnections(Map query) {
        list('connections', query).collect { Map c ->
            [volume: (c.volume as Map)?.name, host: (c.host as Map)?.name, hostGroup: (c.host_group as Map)?.name, lun: c.lun]
        }
    }

    /** Connect through the host group so every member sees the same LUN ID. No lun is passed: the array picks. */
    void connectVolumeToHostGroup(String volume, String hostGroup) {
        call('POST', 'connections', [volume_names: volume, host_group_names: hostGroup])
    }

    void disconnectVolumeFromHostGroup(String volume, String hostGroup) {
        call('DELETE', 'connections', [volume_names: volume, host_group_names: hostGroup])
    }

    void connectVolumeToHost(String volume, String host) {
        call('POST', 'connections', [volume_names: volume, host_names: host])
    }

    void disconnectVolumeFromHost(String volume, String host) {
        call('DELETE', 'connections', [volume_names: volume, host_names: host])
    }

    /** Host groups first, then private host connections (Pure's order). */
    void disconnectEverywhere(String volume) {
        List<Map> conns = listConnections([volume_names: volume])
        conns.findAll { it.hostGroup }*.hostGroup.unique().each { disconnectVolumeFromHostGroup(volume, it as String) }
        conns.findAll { !it.hostGroup && it.host }*.host.unique().each { disconnectVolumeFromHost(volume, it as String) }
    }

    // ---------------------------------------------------------------- protection groups (read only)

    List<Map> listProtectionGroups() {
        list('protection-groups', [destroyed: false])
    }

    /** member name -> protection groups it is in, for one kind of member: 'volumes', 'hosts' or 'host-groups'. */
    Map<String, List<String>> protectionGroupsByMember(String kind) {
        Map<String, List<String>> out = [:]
        list("protection-groups/${kind}").each { Map m ->
            out.computeIfAbsent((m.member as Map)?.name as String) { [] } << ((m.group as Map)?.name as String)
        }
        out
    }

    /** Newest snapshot of a protection group, or null. */
    Map latestProtectionGroupSnapshot(String group) {
        ((call('GET', 'protection-group-snapshots', [source_names: group, destroyed: false, sort: 'created-', limit: 1]).items ?: []) as List<Map>).find()
    }

    // ---------------------------------------------------------------- snapshots

    List<Map> listVolumeSnapshots(String volumeName) {
        list('volume-snapshots', [source_names: volumeName, destroyed: false])
    }

    List<Map> createVolumeSnapshot(List<String> volumeNames, String suffix = null) {
        (call('POST', 'volume-snapshots', [source_names: volumeNames], suffix ? [suffix: suffix] : [:]).items ?: []) as List<Map>
    }
}
