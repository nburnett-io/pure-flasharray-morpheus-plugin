package org.purehvm.api

import spock.lang.IgnoreIf
import spock.lang.Specification

/** Opt-in network test: PROBE_URL=https://host [PROBE_EXPECT=<sha256>] gradlew test --tests '*CertProbeSpec*' */
@IgnoreIf({ !System.getenv('PROBE_URL') })
class CertProbeSpec extends Specification {

    def 'probe reads the presented certificate and a pinned transport accepts it'() {
        when:
        Map cert = CertProbe.probe(System.getenv('PROBE_URL'))
        println "probe: ${cert.pretty} ${cert.subject}"

        then:
        cert.sha256 ==~ /[0-9a-f]{64}/
        !System.getenv('PROBE_EXPECT') || PinnedCertTrustManager.normalize(System.getenv('PROBE_EXPECT')) == cert.sha256

        when: 'the pinned transport connects with that fingerprint'
        def r = new JdkHttpTransport(cert.pretty as String).send('GET', URI.create(System.getenv('PROBE_URL')), [:], null)

        then:
        r.status > 0

        when: 'a different fingerprint is refused'
        new JdkHttpTransport('00' * 32).send('GET', URI.create(System.getenv('PROBE_URL')), [:], null)

        then:
        def e = thrown(FlashArrayException)
        e.cause instanceof PinnedCertTrustManager.CertificateFingerprintMismatch
    }
}
