package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractStorageProvider
import com.morpheusdata.core.providers.PluginProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.event.ClusterEvent
import com.morpheusdata.model.event.Event
import com.morpheusdata.model.event.EventType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.StorageServerType
import com.morpheusdata.response.ServiceResponse
import org.purehvm.PureFlashArrayPlugin
import org.purehvm.api.CertProbe
import org.purehvm.api.FlashArrayClient
import org.purehvm.api.FlashArrayException
import org.purehvm.api.JdkHttpTransport
import org.purehvm.api.PinnedCertTrustManager
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Registers "Pure FlashArray (HVM)" as a Morpheus Storage Server type (Infrastructure > Storage > Servers).
 * The API token is stored by Morpheus (encrypted servicePassword or a Trust > Credentials entry); the array's
 * self-signed certificate is pinned by SHA-256 fingerprint instead of being trusted blindly.
 */
@Slf4j
class PureStorageProvider extends AbstractStorageProvider implements PluginProvider.EventSubscriberFacet<Event> {
    static final String CODE = 'pure-flasharray-hvm.storage'

    final Plugin plugin
    final MorpheusContext morpheus

    PureStorageProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { CODE }
    @Override String getName() { 'Pure FlashArray (HVM)' }
    @Override String getDescription() { 'Pure Storage FlashArray over iSCSI for HVM clusters (host groups, GFS2 LUNs, snapshots)' }
    @Override Icon getIcon() { new Icon(path: 'pure.svg', darkPath: 'pure-dark.svg') }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    // ------------------------------------------------------------------ cluster events

    @Override
    List<EventType> getSupportedEventTypes() {
        [ClusterEvent.ClusterEventType.ADD_WORKER, ClusterEvent.ClusterEventType.REMOVE_WORKER] as List<EventType>
    }

    /**
     * A host joined a cluster that is linked to a FlashArray: add it to the host group and rescan (as the tab's
     * "Add to host group" does), unless the "Add new cluster hosts automatically" setting is off. Mounting stays a
     * deliberate action in the tab. Host removal only logs; the tab shows the leftover array host.
     */
    @Override
    void onEvent(Event event, AccountIntegration integration) {
        if (!(event instanceof ClusterEvent)) {
            return
        }
        ClusterEvent ce = (ClusterEvent) event
        String host = ce.server?.name
        Long clusterId = ce.cluster?.id
        log.info("Pure plugin: cluster event ${ce.type} host=${host} cluster=${clusterId}")
        if (!clusterId || !host) {
            return
        }
        try {
            PureClusterContext ctx = new PureClusterContext(morpheus, clusterId)
            if (!ctx.links) {
                return   // cluster not linked to a FlashArray
            }
            if (ce.type == ClusterEvent.ClusterEventType.REMOVE_WORKER) {
                log.info("Pure plugin: ${host} left ${ctx.cluster.name}; its array host stays in host group(s) " +
                        "${ctx.links*.hostGroup.findAll().join(', ')} until removed on the array")
                return
            }
            Map settings = PureFlashArrayPlugin.readSettings(morpheus, plugin)
            if (settings.autoAddHosts in [false, 'off', 'false']) {
                log.info("Pure plugin: auto-add disabled; ${host} must be added from the cluster tab")
                return
            }
            Audit.info(morpheus, null, ctx.cluster, 'host joined the cluster: adding it to the FlashArray host group(s)', [host: host])
            Jobs.start('auto-add-host', clusterId, 'morpheus (host added)') { List<String> progress ->
                [host: host, storage: new HostOnboarding(morpheus, ctx, progress).add(host)]   // every linked array
            }
        } catch (Exception e) {
            log.warn("Pure plugin: auto-add of ${host} to cluster ${clusterId} not started: ${e.message}")
        }
    }

    @Override
    StorageServerType getStorageServerType() {
        StorageServerType t = new StorageServerType(code: CODE, name: name, description: description,
                hasBlock: true, hasDatastore: true, hasDisks: true, hasHosts: true, hasGroups: false,
                createBlock: false, createDatastore: false, createDisk: false, createHost: false, hasFileBrowser: false)
        t.optionTypes = [
                new OptionType(code: 'pure.serviceUrl', name: 'Array URL', fieldName: 'serviceUrl', fieldContext: 'domain',
                        fieldLabel: 'Array URL', inputType: OptionType.InputType.TEXT, required: true, displayOrder: 0,
                        placeHolderText: 'https://array-mgmt.example.invalid'),
                new OptionType(code: 'pure.serviceUsername', name: 'Username', fieldName: 'serviceUsername', fieldContext: 'domain',
                        fieldLabel: 'Username', inputType: OptionType.InputType.TEXT, required: false, displayOrder: 8,
                        helpText: 'Array user (storage_admin). With a username, enter its password below: the plugin creates an API token for that user and stores only the token.'),
                new OptionType(code: 'pure.servicePassword', name: 'Password or API token', fieldName: 'servicePassword', fieldContext: 'domain',
                        fieldLabel: 'Password or API token', inputType: OptionType.InputType.PASSWORD, required: true, displayOrder: 10,
                        helpText: 'The user\'s password (replaced by an API token on first save), or an existing Purity API token (leave Username empty).'),
                new OptionType(code: 'pure.createServiceUser', name: 'Create service user', fieldName: 'createServiceUser', fieldContext: 'config',
                        fieldLabel: 'Create storage_admin service user', inputType: OptionType.InputType.TEXT, required: false, displayOrder: 12,
                        placeHolderText: 'morpheus-hvm',
                        helpText: 'Optional. Enter an array_admin login above and a NEW user name here: the plugin creates that local user with the storage_admin role, issues its API token, and discards the admin login.'),
                new OptionType(code: 'pure.allowElevatedRole', name: 'Allow array_admin token', fieldName: 'allowElevatedRole', fieldContext: 'config',
                        fieldLabel: 'Allow a token above storage_admin', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 14,
                        helpText: 'By default saving is refused if the token belongs to an array_admin user.'),
                new OptionType(code: 'pure.certFingerprint', name: 'Certificate SHA-256', fieldName: 'certFingerprint', fieldContext: 'config',
                        fieldLabel: 'Certificate SHA-256 fingerprint', inputType: OptionType.InputType.TEXT, required: false, displayOrder: 20,
                        helpText: 'Leave empty: the plugin pins the certificate the array presents on first connection (trust on first use) and then refuses any other. Paste a value to verify it up front instead. Clear it to re-trust after a planned certificate change.'),
        ]
        t
    }

    /** Tokens obtained from a Trust > Credentials username/password (which the plugin cannot overwrite). */
    private static final Map<String, String> EXCHANGED = new ConcurrentHashMap<>()

    /**
     * The API token for a storage server. A UUID-shaped secret, or any secret without a username, is a token.
     * Otherwise the secret is that user's password and is exchanged for the user's API token.
     */
    static String apiToken(StorageServer s) {
        String user = (s.credentialData?.username ?: s.serviceUsername) as String
        String secret = (s.credentialData?.password ?: s.servicePassword) as String
        if (!secret) {
            throw new IllegalStateException("Storage server ${s.name} has no password or API token")
        }
        if (!user || FlashArrayClient.looksLikeApiToken(secret)) {
            return secret
        }
        String svc = serviceUserToCreate(s)
        if (svc && s.credentialData?.password) {
            throw new IllegalStateException('Service-user creation needs the admin login in the Username/Password fields, not a Trust credential')
        }
        String key = "${s.serviceUrl}|${user}|${svc ?: ''}|${secret.hashCode()}".toString()
        EXCHANGED.computeIfAbsent(key) {
            JdkHttpTransport t = new JdkHttpTransport(fingerprint(s))
            if (svc) {
                return FlashArrayClient.bootstrapServiceUser(s.serviceUrl, user, secret, svc, t)
            }
            try {
                FlashArrayClient.exchangeCredentialsForApiToken(s.serviceUrl, user, secret, t, refusedRoles(s))
            } catch (FlashArrayException.RoleRefusedException e) {
                throw new IllegalStateException(e.role == 'array_admin'
                        ? "${user} is an array_admin. Enter a new user name in \"Create storage_admin service user\" to have the plugin create a scoped account, or tick \"Allow a token above storage_admin\". No token was created."
                        : "${user} has role ${e.role}; the plugin needs storage_admin. No token was created.")
            }
        }
    }

    static String serviceUserToCreate(StorageServer s) {
        (s.getConfigProperty('createServiceUser') as String)?.trim() ?: null
    }

    static boolean elevatedAllowed(StorageServer s) {
        s.getConfigProperty('allowElevatedRole') in [true, 'on', 'true']
    }

    /** Roles the plugin refuses: too weak to manage hosts and volumes, and array_admin unless allowed. */
    static List<String> refusedRoles(StorageServer s) {
        ['readonly', 'ops_admin'] + (elevatedAllowed(s) ? [] : ['array_admin'])
    }

    /** Refuses tokens of a {@link #refusedRoles refused role}. */
    static String checkRole(FlashArrayClient fa, StorageServer s) {
        String role = fa.sessionRole()
        if (role in refusedRoles(s)) {
            throw new IllegalStateException(role == 'array_admin'
                    ? 'The API token belongs to an array_admin user. Use a storage_admin user, let the plugin create one ' +
                      '("Create storage_admin service user"), or tick "Allow a token above storage_admin".'
                    : "The API token's user has role ${role}; the plugin needs storage_admin")
        }
        role
    }

    /**
     * Forgets the token exchanged for this server's username/password, so the next use logs in again: after the array
     * rejected it (deleted or rotated there), the cached one would otherwise fail until Morpheus restarts.
     */
    static void forgetExchangedToken(StorageServer s) {
        String user = (s.credentialData?.username ?: s.serviceUsername) as String
        EXCHANGED.keySet().removeIf { it.startsWith("${s.serviceUrl}|${user}|".toString()) }
    }

    private static boolean tokenRejected(Throwable e) {
        e instanceof FlashArrayException && (e as FlashArrayException).status in [401, 403]
    }

    /**
     * The pinned certificate fingerprint. If none is set yet, trust on first use: read the certificate the array
     * presents now, pin it on the record, and from then on require exactly that certificate.
     */
    static String fingerprint(StorageServer s) {
        String fp = s.getConfigProperty('certFingerprint') as String
        if (!fp) {
            Map cert = CertProbe.probe(s.serviceUrl)
            fp = cert.pretty as String
            s.setConfigProperty('certFingerprint', fp)
            s.setConfigProperty('certSubject', cert.subject as String)
            s.setConfigProperty('certPinnedAt', new Date().toInstant().toString())
            log.info("FlashArray ${s.name ?: s.serviceUrl}: pinned certificate ${fp} (${cert.subject}) on first use")
        }
        fp
    }

    /** Builds an authenticated client from a saved storage server record. */
    static FlashArrayClient client(StorageServer s) {
        FlashArrayClient.connect(s.serviceUrl, apiToken(s), fingerprint(s))
    }

    /**
     * If the record still holds a password in its own (encrypted) field, replace it with the API token so the
     * password is not kept. Returns true if the record changed. Trust > Credentials entries are left alone.
     */
    boolean replacePasswordWithToken(StorageServer s) {
        if (s.credentialData?.password || !s.serviceUsername || !s.servicePassword || FlashArrayClient.looksLikeApiToken(s.servicePassword)) {
            return false
        }
        String svc = serviceUserToCreate(s)
        s.servicePassword = apiToken(s)
        if (svc) {
            log.info("FlashArray ${s.name}: created storage_admin user ${svc}; admin login ${s.serviceUsername} discarded")
            s.serviceUsername = svc
            s.setConfigProperty('serviceUserCreated', svc)
            s.setConfigProperty('createServiceUser', null)
        } else {
            log.info("FlashArray ${s.name}: API token issued for ${s.serviceUsername}; stored password replaced by the token")
        }
        true
    }

    @Override
    ServiceResponse verifyStorageServer(StorageServer server, Map opts) {
        try {
            FlashArrayClient fa = client(server)   // pins the certificate on first use if no fingerprint was entered
            try {
                Map a = fa.array
                String role = checkRole(fa, server)
                log.info("Verified FlashArray ${a.name} (Purity ${a.version}, REST ${fa.apiVersion}, token role ${role ?: 'unknown'})")
                return ServiceResponse.success()
            } finally {
                fa.close()
            }
        } catch (FlashArrayException e) {
            if (e.cause instanceof PinnedCertTrustManager.CertificateFingerprintMismatch) {
                return ServiceResponse.error("The array's certificate has changed (it now presents " +
                        "${PinnedCertTrustManager.pretty((e.cause as PinnedCertTrustManager.CertificateFingerprintMismatch).actual)}). " +
                        'If the certificate was replaced on purpose, clear the fingerprint field and save to trust the new one.', [certFingerprint: 'Changed'])
            }
            if (tokenRejected(e)) {
                forgetExchangedToken(server)
            }
            return ServiceResponse.error(e.message)
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ServiceResponse.error(e.message)
        } catch (Exception e) {
            return ServiceResponse.error("Cannot verify array: ${e.message}")
        }
    }

    @Override
    ServiceResponse initializeStorageServer(StorageServer server, Map opts) {
        refreshStorageServer(server, opts)
    }

    @Override
    ServiceResponse refreshStorageServer(StorageServer server, Map opts) {
        FlashArrayClient fa = null
        try {
            replacePasswordWithToken(server)
            fa = client(server)
            Map a = fa.array
            List<Map> ctrls = fa.listControllers()
            Map ctrl = ctrls.find { it.mode == 'primary' } ?: ctrls.find()
            Map space = fa.list('arrays/space').find() ?: [:]
            server.serverVendor = 'Pure Storage'
            server.serverModel = ctrl?.model
            server.serviceVersion = a.version
            server.serialNumber = a.id
            server.externalId = a.id
            server.maxStorage = space.capacity as Long
            server.usedStorage = (space.space as Map)?.total_physical as Long
            String role = fa.sessionRole()
            server.status = 'ok'
            server.statusMessage = "Purity ${a.version}, REST ${fa.apiVersion}, token role ${role ?: 'unknown'}, certificate ${server.getConfigProperty('certFingerprint')}" +
                    (role == 'array_admin' ? ' (WARNING: above storage_admin)' : '')
            morpheus.services.storageServer.save(server)
            ServiceResponse.success()
        } catch (Exception e) {
            log.warn("FlashArray refresh failed for ${server.name}: ${e.message}")
            if (tokenRejected(e)) {
                forgetExchangedToken(server)
            }
            server.status = 'error'
            server.statusMessage = e.message?.take(250)
            try {
                morpheus.services.storageServer.save(server)
            } catch (Exception ignored) {
            }
            ServiceResponse.error(e.message)
        } finally {
            fa?.close()
        }
    }
}
