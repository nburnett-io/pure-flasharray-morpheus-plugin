package org.purehvm.morpheus

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.FlashArrayClientSpec
import spock.lang.Specification

import static org.purehvm.api.FlashArrayClientSpec.ok

class SnapshotPoliciesSpec extends Specification {

    static final Map PG_AUTO = [name: 'pgroup-auto', snapshot_schedule: [enabled: true, frequency: 6 * 3600 * 1000L],
                                source_retention: [all_for_sec: 86400, per_day: 4, days: 7], replication_schedule: [enabled: false],
                                retention_lock: 'unlocked']
    static final Map PG_SAFE = [name: 'pg-safemode', snapshot_schedule: [enabled: true, frequency: 3600 * 1000L],
                                source_retention: [all_for_sec: 2 * 86400], replication_schedule: [enabled: true, frequency: 4 * 3600 * 1000L],
                                retention_lock: 'ratcheted']
    static final Map PG_OFF = [name: 'pg-manual', snapshot_schedule: [enabled: false, frequency: 3600 * 1000L], source_retention: [:]]

    def 'a protection group in words'() {
        expect:
        SnapshotPolicies.describe(PG_AUTO).with {
            scheduled && every == '6 hours' && retention == 'all for 1 day, then 4 per day for 7 more days' &&
                    keptSeconds == 8 * 86400 && replication == null && !safeMode
        }
        SnapshotPolicies.describe(PG_SAFE).with { every == '1 hour' && replication == 'every 4 hours' && safeMode && retention == 'all for 2 days' }
        SnapshotPolicies.describe(PG_OFF).with { !scheduled && every == null && retention == 'not reported' }
    }

    def 'durations'() {
        expect:
        SnapshotPolicies.duration(s) == text

        where:
        s      | text
        60     | '1 minute'
        5400   | '90 minutes'
        7200   | '2 hours'
        86400  | '1 day'
        90     | '90 seconds'
    }

    def 'the customer line has no names, only how often and how long'() {
        expect:
        SnapshotPolicies.customerText([SnapshotPolicies.describe(PG_AUTO), SnapshotPolicies.describe(PG_SAFE)]) ==
                'Restore points are taken every 1 hour and kept for up to 8 days.'
        SnapshotPolicies.customerText([SnapshotPolicies.describe(PG_OFF)]) == 'Restore points are taken on demand by your provider.'
        SnapshotPolicies.customerText(null) == 'Restore points are taken on demand by your provider.'
    }

    def 'array SafeMode from eradication_config'() {
        expect:
        SnapshotPolicies.safeMode([eradication_config: [manual_eradication: 'all-disabled', eradication_delay: 7 * 86400000L]]).with {
            reported && on && eradicationDelay == '7 days'
        }
        SnapshotPolicies.safeMode([eradication_config: [manual_eradication: 'all-enabled']]).with { reported && !on }
        !SnapshotPolicies.safeMode([name: 'old-purity']).reported
    }

    def 'a volume is covered directly, through its host group and through a host'() {
        given:
        def fake = new FlashArrayClientSpec.FakeTransport()
        fake.on('GET', '/api/api_version') { u, h, b -> ok([version: ['2.36']]) }
        fake.on('POST', '/login') { u, h, b -> ok([:], ['x-auth-token': 'session-1']) }
        fake.on('GET', 'protection-groups/volumes') { u, h, b -> ok([items: [[group: [name: 'pg-manual'], member: [name: 'ds-a']]]]) }
        fake.on('GET', 'protection-groups/host-groups') { u, h, b -> ok([items: [[group: [name: 'pgroup-auto'], member: [name: 'hg-cluster']]]]) }
        fake.on('GET', 'protection-groups/hosts') { u, h, b -> ok([items: [[group: [name: 'pg-safemode'], member: [name: 'hvm-a']]]]) }
        fake.on('GET', 'protection-group-snapshots') { u, h, b -> ok([items: [[name: 'pgroup-auto.1', created: 1234L]]]) }
        fake.on('GET', '/protection-groups?') { u, h, b -> ok([items: [PG_AUTO, PG_SAFE, PG_OFF]]) }
        fake.on('GET', '/connections') { u, h, b ->
            ok([items: [[volume: [name: 'ds-a'], host: [name: 'hvm-a'], host_group: [name: 'hg-cluster'], lun: 1],
                        [volume: [name: 'ds-b'], host: [name: 'hvm-b'], host_group: [name: 'hg-cluster'], lun: 2]]])
        }
        def fa = new FlashArrayClient('array.example.invalid', 'not-a-real-token', fake)

        when:
        List<Map> out = SnapshotPolicies.forVolumes(fa, ['ds-a', 'ds-b'])

        then:
        out.find { it.volume == 'ds-a' }.with {
            scheduled && (policies*.name as Set) == ['pg-manual', 'pgroup-auto', 'pg-safemode'] as Set &&
                    policies.find { it.name == 'pg-safemode' }.via == ['host hvm-a'] &&
                    policies.find { it.name == 'pgroup-auto' }.via == ['host group hg-cluster'] &&
                    policies.find { it.name == 'pgroup-auto' }.lastSnapshot == 1234L
        }
        out.find { it.volume == 'ds-b' }.policies*.name == ['pgroup-auto']
    }
}
