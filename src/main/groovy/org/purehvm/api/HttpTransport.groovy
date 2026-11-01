package org.purehvm.api

import groovy.transform.CompileStatic

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration

/** Minimal HTTP seam so the FlashArray client can be unit-tested without an array. */
@CompileStatic
interface HttpTransport {
    Response send(String method, URI uri, Map<String, String> headers, String body)

    @CompileStatic
    static class Response {
        int status
        Map<String, List<String>> headers = [:]
        String body

        String header(String name) {
            headers.find { k, v -> k?.equalsIgnoreCase(name) }?.value?.find()
        }
    }
}

/**
 * java.net.http transport. With a pinned fingerprint the array's self-signed certificate is trusted
 * by exact match; without one the JVM's normal CA trust store and hostname checks apply.
 */
@CompileStatic
class JdkHttpTransport implements HttpTransport {
    private final HttpClient client
    private final Duration requestTimeout

    JdkHttpTransport(String pinnedSha256 = null, Duration connectTimeout = Duration.ofSeconds(30), Duration requestTimeout = Duration.ofSeconds(60)) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
        if (pinnedSha256) {
            SSLContext ctx = SSLContext.getInstance('TLS')
            ctx.init(null, [new PinnedCertTrustManager(pinnedSha256)] as TrustManager[], new SecureRandom())
            b.sslContext(ctx)
        }
        this.client = b.build()
        this.requestTimeout = requestTimeout
    }

    @Override
    Response send(String method, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(requestTimeout)
        headers?.each { String k, String v -> rb.header(k, v) }
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)
        rb.method(method, pub)
        try {
            HttpResponse<String> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString())
            return new Response(status: r.statusCode(), headers: r.headers().map(), body: r.body())
        } catch (IOException e) {
            Throwable root = e
            while (root.cause != null && root.cause != root) {
                root = root.cause
            }
            if (root instanceof PinnedCertTrustManager.CertificateFingerprintMismatch) {
                throw new FlashArrayException(root.message, 0, [], root)
            }
            throw new FlashArrayException("Cannot reach ${uri.host}: ${e.message ?: e.class.simpleName}", 0, [], e)
        }
    }
}
