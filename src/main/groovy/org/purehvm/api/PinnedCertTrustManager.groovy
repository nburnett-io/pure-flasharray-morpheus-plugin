package org.purehvm.api

import groovy.transform.CompileStatic

import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * Trusts exactly one server certificate, identified by the SHA-256 of its DER encoding.
 *
 * FlashArrays ship a self-signed certificate, so CA validation fails out of the box. Pinning the leaf
 * is the safe alternative to "trust everything" (which is what Pure's own vSphere plugin does).
 * Because this is an X509ExtendedTrustManager, JSSE delegates endpoint identification to it, so the
 * array can be addressed by IP even though its certificate names something else.
 */
@CompileStatic
class PinnedCertTrustManager extends X509ExtendedTrustManager {
    private final String expected

    PinnedCertTrustManager(String sha256Fingerprint) {
        this.expected = normalize(sha256Fingerprint)
        if (expected.length() != 64) {
            throw new IllegalArgumentException('Certificate fingerprint must be a SHA-256 hex digest (64 hex characters)')
        }
    }

    static String normalize(String fp) {
        (fp ?: '').replaceAll('[^0-9A-Fa-f]', '').toLowerCase()
    }

    static String fingerprint(X509Certificate cert) {
        MessageDigest.getInstance('SHA-256').digest(cert.encoded).encodeHex().toString()
    }

    /** Colon-separated upper-case form, as shown in browsers and by `openssl x509 -fingerprint -sha256`. */
    static String pretty(String hex) {
        normalize(hex).toUpperCase().toList().collate(2)*.join('').join(':')
    }

    private void check(X509Certificate[] chain) {
        if (!chain) {
            throw new CertificateException('Array presented no certificate')
        }
        String actual = fingerprint(chain[0])
        if (actual != expected) {
            throw new CertificateFingerprintMismatch(actual)
        }
    }

    @Override void checkServerTrusted(X509Certificate[] chain, String authType) { check(chain) }
    @Override void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { check(chain) }
    @Override void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { check(chain) }

    @Override void checkClientTrusted(X509Certificate[] chain, String authType) { throw new CertificateException('client auth not supported') }
    @Override void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { throw new CertificateException('client auth not supported') }
    @Override void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { throw new CertificateException('client auth not supported') }

    @Override X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }

    /** Carries the fingerprint the array actually presented so the UI can show it to the operator. */
    @CompileStatic
    static class CertificateFingerprintMismatch extends CertificateException {
        final String actual
        CertificateFingerprintMismatch(String actual) {
            super("Array certificate SHA-256 ${pretty(actual)} does not match the pinned fingerprint")
            this.actual = actual
        }
    }
}
