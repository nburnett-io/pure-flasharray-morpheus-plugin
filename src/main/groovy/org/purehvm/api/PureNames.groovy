package org.purehvm.api

import groovy.transform.CompileStatic

/**
 * FlashArray naming rules and device identity helpers.
 *
 * Name rules follow Purity (and Pure's vSphere plugin): 1-63 chars of [A-Za-z0-9_-], at least one letter,
 * no leading/trailing '-' or '_'. Pods and volume groups prefix as "pod::name" / "vgroup/name".
 */
@CompileStatic
class PureNames {
    static final int MAX_NAME = 63
    /** Linux dm-multipath WWID prefix for a FlashArray volume: '3' + NAA type 6 + Pure's OUI 24a937 + '0'. */
    static final String WWID_PREFIX = '3624a9370'
    /** Tag namespace on the array objects the plugin manages, unless the "Array tag namespace" setting says otherwise. */
    static final String DEFAULT_TAG_NAMESPACE = 'org.purehvm'
    private static volatile String tagNamespace = DEFAULT_TAG_NAMESPACE
    static final String TAG_CLUSTER = 'morpheus-cluster-id'

    /** The namespace in use: the plugin only sees (and writes) its tags in this one. */
    static String getTagNamespace() {
        tagNamespace
    }

    /** Applies the setting: 1-64 of [A-Za-z0-9._-]; blank or anything else means the default. */
    static String configureTagNamespace(String value) {
        String v = value?.trim()
        tagNamespace = v ==~ /[A-Za-z0-9._-]{1,64}/ ? v : DEFAULT_TAG_NAMESPACE
    }

    /** A volume belongs to a cluster when the plugin tagged it with that cluster's id. */
    static boolean ownedBy(Map<String, String> tags, Long clusterId) {
        clusterId != null && tags?.get(TAG_CLUSTER) == clusterId.toString()
    }

    static boolean isValidName(String name) {
        name != null && name ==~ /[A-Za-z0-9_-]{1,63}/ && name =~ /[A-Za-z]/ &&
                !(name.startsWith('-') || name.startsWith('_') || name.endsWith('-') || name.endsWith('_'))
    }

    /** Hostname/cluster name -> valid array object name. Mirrors Pure's sanitizeHostname. */
    static String sanitize(String raw) {
        String s = (raw ?: '').replaceAll('[^A-Za-z0-9-]', '-').replaceAll('-{2,}', '-').replaceAll('^-+|-+$', '')
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME).replaceAll('-+$', '')
        }
        if (!s || !(s =~ /[A-Za-z]/)) {
            s = ('h-' + s).replaceAll('-+$', '')
        }
        s ?: 'DEFAULT'
    }

    /** Short hostname: first DNS label, which is what the hosts call themselves. */
    static String shortHostname(String fqdnOrName) {
        (fqdnOrName ?: '').tokenize('.').find() ?: ''
    }

    /**
     * Returns {@code base} if free, else base-iscsi, base-iscsi-1, ... (Pure's collision scheme), truncated to fit.
     * Comparison is case-insensitive because Purity names are.
     */
    static String nonColliding(String base, Collection<String> taken, String protocol = 'iscsi') {
        Set<String> lower = taken.collect { it.toLowerCase() } as Set<String>
        if (!lower.contains(base.toLowerCase())) {
            return base
        }
        for (int i = 0; i < 1000; i++) {
            String suffix = i == 0 ? "-${protocol}" : "-${protocol}-${i}"
            String candidate = base.take(MAX_NAME - suffix.length()).replaceAll('-+$', '') + suffix
            if (!lower.contains(candidate.toLowerCase())) {
                return candidate
            }
        }
        throw new IllegalStateException("No free name for ${base}")
    }

    static String wwid(String serial) {
        WWID_PREFIX + requireSerial(serial).toLowerCase()
    }

    /** The only device path Morpheus accepts for a shared GFS2 datastore. */
    static String devicePath(String serial) {
        '/dev/mapper/' + wwid(serial)
    }

    /** Reverse of {@link #wwid}; null if the WWID is not a FlashArray volume. */
    static String serialFromWwid(String wwid) {
        String w = (wwid ?: '').toLowerCase().replaceFirst('^/dev/mapper/', '').replaceFirst('^dm-uuid-mpath-', '')
        w.startsWith(WWID_PREFIX) && w.length() == WWID_PREFIX.length() + 24 ? w.substring(WWID_PREFIX.length()).toUpperCase() : null
    }

    static String requireSerial(String serial) {
        if (!(serial ==~ /[0-9A-Fa-f]{24}/)) {
            throw new IllegalArgumentException("Not a FlashArray volume serial: ${serial}")
        }
        serial
    }

    /**
     * GFS2 lock-table filesystem part (after "cluster:"): lowercase, [a-z0-9_-], max 30 chars, which matches
     * what Morpheus generates. Used when re-signaturing a snapshot clone.
     */
    static String gfs2FsName(String raw) {
        String s = (raw ?: '').toLowerCase().replaceAll('[^a-z0-9_-]', '_').replaceAll('_{2,}', '_').replaceAll('^[_-]+|[_-]+$', '')
        s = s.take(30).replaceAll('[_-]+$', '')
        s ?: 'restore'
    }
}
