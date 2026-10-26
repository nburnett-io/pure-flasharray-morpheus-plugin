package org.purehvm.morpheus

import org.purehvm.ops.HostScripts
import spock.lang.Specification

class ReclaimSpec extends Specification {
    static final String MP = '/mnt/aaaaaaaa-1111-4222-8333-444444444444'

    def 'leftovers are named by what made them, with the time in their name'() {
        expect:
        Reclaim.kindOf('vm01/disk-0.qcow2.pre-restore-20261021153000').with { kind == 'pre-restore' && since == 1792596600000L }
        Reclaim.kindOf('vm01/vm01.file-restore-20261021153000-vdb.qcow2').kind == 'file-restore copy'
        Reclaim.kindOf('iso/ubuntu.iso').kind == 'unknown'
    }

    def 'only restore leftovers on a GFS2 datastore can be deleted'() {
        when:
        HostScripts.removeLeftovers(mount, [path])

        then:
        thrown(IllegalArgumentException)

        where:
        mount            | path
        MP               | 'vm01/disk-0.qcow2'                       // a live disk
        MP               | 'iso/ubuntu.iso'
        MP               | '../etc/passwd.pre-restore-20261021'
        MP               | '/vm01/disk-0.qcow2.pre-restore-20261021' // absolute
        '/var/lib/libvirt' | 'vm01/disk-0.qcow2.pre-restore-20261021'
    }

    def 'the delete re-checks each file on the host and never follows links'() {
        when:
        String s = HostScripts.removeLeftovers(MP, ['vm01/disk-0.qcow2.pre-restore-20261021153000', 'vm01/vm01.file-restore-20261021153000-vdb.qcow2'])

        then:
        s.contains("gone 'vm01/disk-0.qcow2.pre-restore-20261021153000'")
        s.contains('[ -L "$F" ]')
        s.contains('virsh domblklist')
        s.contains('INUSE=$F')
        !s.readLines().findAll { !it.trim().startsWith('#') }.any { it.contains('| grep -q') }   // pipefail: see removeKeptIfBlank
        s.contains(".pure-hvm/copies/")
    }

    def 'the scan prunes heartbeat data and the plugin index and lists what libvirt uses'() {
        when:
        String s = HostScripts.leftoverScan([MP])

        then:
        s.contains("for MP in '${MP}'")
        s.contains('-path "$MP/mvm-hb"') && s.contains('-path "$MP/.pure-hvm"')
        s.contains('INUSE=')
        s.trim().endsWith('true')
    }
}
