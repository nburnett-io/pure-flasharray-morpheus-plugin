package org.purehvm.compliance

import org.purehvm.compliance.ComplianceChecker.Context
import org.purehvm.compliance.ComplianceChecker.Status
import spock.lang.Specification

/** Backlog #0 follow-ups: lowering to 0 only when allowed, a differing setting that actually offers Remediate, multipaths{} entries. */
class NoPathRetryOptionsSpec extends Specification {

    static HostFacts finiteHost(String entries = '') {
        HostFacts.parse("""@@mpconf
\tdevice {
\t\tvendor "PURE"
\t\tproduct "FlashArray"
\t\tno_path_retry 12
\t}
@@mpglobal
defaults|polling_interval|5
@@mpentries
${entries}@@end
""")
    }

    static ComplianceChecker.Result mp03(HostFacts f, String setting, boolean allowZero) {
        new ComplianceChecker().check(f, new Context([], [] as Set, '6.10.4', setting, 110, allowZero)).find { it.id == 'MP-03' }
    }

    def 'a setting of 0 leaves a finite host value alone unless lowering is allowed'() {
        expect:
        with(mp03(finiteHost(), '0', false)) { status == Status.PASS && fix == null }
        with(mp03(finiteHost(), '0', true)) { status == Status.WARN && fix == 'multipath' && hint.contains('0 and lowering is allowed') }
        Remediation.dropInNoPathRetry('0', finiteHost()) == '12'
        Remediation.dropInNoPathRetry('0', finiteHost(), true) == '0'
    }

    def 'the drop-in step says when it lowers a finite value'() {
        when:
        String s = new Remediation().scriptSteps(finiteHost(), ['multipath'], [], '6.10.4', '0', [allowZero: true])*.script.join('')

        then:
        s.contains('no_path_retry 0 (lowered from 12: lowering to 0 is allowed)')
    }

    def 'a finite setting that differs offers Remediate (a PASS would have dropped the fix)'() {
        expect:
        with(mp03(finiteHost(), '8', false)) { status == Status.WARN && fix == 'multipath' }
        with(mp03(finiteHost(), '12', false)) { status == Status.PASS && fix == null }
    }

    def 'a multipaths{} entry for a FlashArray LUN is reported; others are not'() {
        when:
        HostFacts f = finiteHost('3624a93701a2b3c4d5e6f000000011234|5\n3600508b1001c0000000000000000aaaa|queue\n')
        def r = new ComplianceChecker().check(f, new Context([], [] as Set, '6.10.4')).find { it.id == 'MP-03c' }

        then:
        f.mpEntries.size() == 2
        r.status == Status.WARN
        r.observed == '3624a93701a2b3c4d5e6f000000011234: 5'
    }
}
