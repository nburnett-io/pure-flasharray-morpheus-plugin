package org.purehvm.api

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Reads the certificate an array presents so the operator can confirm and pin it. Nothing is sent over the
 * connection and nothing is trusted: the handshake is only used to see the certificate.
 */
class CertProbe {
    static Map probe(String url, int timeoutMs = 10000) {
        URI u = URI.create(FlashArrayClient.cleanUrl(url))
        X509Certificate[] seen = null
        TrustManager capture = new X509TrustManager() {
            void checkClientTrusted(X509Certificate[] c, String a) {}
            void checkServerTrusted(X509Certificate[] c, String a) { seen = c }
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
        }
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, [capture] as TrustManager[], null)
        SSLSocket s = (SSLSocket) ctx.socketFactory.createSocket()
        try {
            s.connect(new InetSocketAddress(u.host, u.port > 0 ? u.port : 443), timeoutMs)
            s.soTimeout = timeoutMs
            s.startHandshake()
        } finally {
            s.close()
        }
        X509Certificate leaf = seen?.find()
        if (!leaf) {
            throw new FlashArrayException("No certificate presented by ${u.host}")
        }
        String hex = PinnedCertTrustManager.fingerprint(leaf)
        [sha256: hex, pretty: PinnedCertTrustManager.pretty(hex), subject: leaf.subjectX500Principal.name, notAfter: leaf.notAfter]
    }
}
