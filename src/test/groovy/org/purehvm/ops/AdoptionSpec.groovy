package org.purehvm.ops

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.FlashArrayClientSpec.FakeTransport
import org.purehvm.api.HttpTransport
import groovy.json.JsonOutput
import spock.lang.Specification

/** Datastore shapes as Morpheus 9.1.0.1 records them. */
class AdoptionSpec extends Specification {
    static HttpTransport.Response ok(Object body) {
        new HttpTransport.Response(status: 200, body: JsonOutput.toJson(body), headers: ['x-auth-token': ['s']])
    }

    FakeTransport fake = new FakeTransport()
    FlashArrayClient fa = new FlashArrayClient('array.example.invalid', 'not-a-real-token', fake)
    Map<String, List<Map>> tags = [:]

    def setup() {
        fake.on('GET', '/api/api_version') { u, h, b -> ok([version: ['2.36']]) }
        fake.on('POST', '/login') { u, h, b -> ok([:]) }
        fake.on('GET', '/volumes/tags') { u, h, b ->
            String vol = URLDecoder.decode(u.query, 'UTF-8').replaceFirst('.*resource_names=', '').replaceFirst('&.*', '')
            ok([items: tags[vol] ?: []])
        }
        fake.on('PUT', '/volumes/tags/batch') { u, h, b ->
            String vol = URLDecoder.decode(u.query, 'UTF-8').replaceFirst('.*resource_names=', '').replaceFirst('&.*', '')
            tags[vol] = new groovy.json.JsonSlurper().parseText(b).collect { [key: it.key, value: it.value] }
            ok([items: []])
        }
        fake.on('GET', '/volumes?') { u, h, b ->
            URLDecoder.decode(u.rawQuery, 'UTF-8').contains("serial='A1B2C3D4E5F6A7B8C9D0E1F2'")
                    ? ok([items: [[name: 'array01-ds01', serial: 'A1B2C3D4E5F6A7B8C9D0E1F2', provisioned: 2199023255552]]])
                    : ok([items: []])
        }
        fake.on('GET', '/connections') { u, h, b -> ok([items: [[volume: [name: 'array01-ds01'], host_group: [name: 'cluster-a']]]]) }
    }

    static List<Adoption.DatastoreRef> cluster15() {
        [new Adoption.DatastoreRef(id: 25, name: 'local', typeCode: 'libvirt-dir', mountPath: '/var/morpheus/kvm/local'),
         new Adoption.DatastoreRef(id: 30, name: 'array01-ds01', typeCode: 'libvirt-dir-gfs2',
                 blockDevice: '/dev/mapper/3624a9370a1b2c3d4e5f6a7b8c9d0e1f2', mountPath: '/mnt/aaaaaaaa-1111-4222-8333-444444444444'),
         new Adoption.DatastoreRef(id: 31, name: 'other-vendor', typeCode: 'libvirt-dir-gfs2', blockDevice: '/dev/mapper/36000c29aaaabbbbccccdddd000011112')]
    }

    def 'scan finds the GFS2 datastore by serial and ignores local directory datastores'() {
        when:
        def rows = Adoption.scan(fa, 15L, 'cluster-a', cluster15())

        then:
        rows*.datastoreId == [30L, 31L]
        rows[0].with { state == 'adopt' && volume == 'array01-ds01' && detail.contains('host group cluster-a') }
        rows[1].state == 'not-pure'
    }

    def 'adopting tags the volume; a rescan shows it adopted'() {
        when:
        def row = Adoption.scan(fa, 15L, 'cluster-a', cluster15()).find { it.datastoreId == 30L }
        Adoption.adopt(fa, 15L, row)
        def again = Adoption.scan(fa, 15L, 'cluster-a', cluster15()).find { it.datastoreId == 30L }

        then:
        tags['array01-ds01'].find { it.key == 'morpheus-datastore-id' }.value == '30'
        tags['array01-ds01'].find { it.key == 'morpheus-cluster-id' }.value == '15'
        again.state == 'adopted'
    }

    def 'volumes tagged for another cluster are not adoptable'() {
        given:
        tags['array01-ds01'] = [[key: 'morpheus-cluster-id', value: '99']]

        when:
        def row = Adoption.scan(fa, 15L, 'cluster-a', cluster15()).find { it.datastoreId == 30L }
        Adoption.adopt(fa, 15L, row)

        then:
        row.state == 'elsewhere'
        thrown(IllegalStateException)
    }

    def 'connection outside the cluster host group is reported but still adoptable'() {
        expect:
        Adoption.scan(fa, 15L, 'some-other-hg', cluster15()).find { it.datastoreId == 30L }.with {
            state == 'adopt' && detail.contains("not the cluster's")
        }
    }

    def 'scans of several linked arrays merge per datastore and remember which array holds it'() {
        given: 'array 4 holds datastore 30; array 7 does not'
        List<Map> on4 = Adoption.scan(fa, 15L, 'cluster-a', cluster15())
        List<Map> on7 = on4.collect { it.state == 'adopt' ? [datastoreId: it.datastoreId, name: it.name, state: 'not-on-array', detail: 'x'] : it }

        when:
        List<Map> merged = Adoption.merge([7L: on7, 4L: on4])

        then:
        merged.size() == 2
        merged.find { it.datastoreId == 30L }.with { state == 'adopt' && storageServerId == 4L && volume == 'array01-ds01' }
        merged.find { it.datastoreId == 31L }.with { state == 'not-pure' && storageServerId == null }
    }

    def 'a datastore on none of several arrays says so'() {
        given:
        Map row = [datastoreId: 30L, name: 'x', state: 'not-on-array', detail: 'no volume with this serial on the linked array']

        expect:
        Adoption.merge([4L: [row], 7L: [row]])[0].detail == 'no volume with this serial on any linked array'
        Adoption.merge([4L: [row]])[0].detail == row.detail
    }
}
