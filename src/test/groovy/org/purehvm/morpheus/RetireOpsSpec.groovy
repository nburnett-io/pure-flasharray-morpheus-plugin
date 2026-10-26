package org.purehvm.morpheus

import org.purehvm.ops.HostRunner
import org.purehvm.ops.HostScripts
import spock.lang.Specification

/** Shapes from a Morpheus 9.1 cluster: datastores 30 and 32. */
class RetireOpsSpec extends Specification {
    static final long GIB = 1L << 30

    def 'auto target is the datastore with the most free space that fits the data plus headroom'() {
        given:
        List<Map> cands = [
                [id: 30, name: 'array01-ds01', freeBytes: 5000 * GIB, sizeBytes: 5120 * GIB, active: true, online: true],
                [id: 33, name: 'small', freeBytes: 11 * GIB, sizeBytes: 100 * GIB, active: true, online: true],
                [id: 34, name: 'drained', freeBytes: 9000 * GIB, sizeBytes: 9000 * GIB, active: false, online: true],
        ]

        when:
        Map c = RetireOps.chooseTarget(cands, 10 * GIB)

        then:
        c.auto.id == 30
        c.targets*.id == [34, 30, 33]                      // most free first
        c.targets.find { it.id == 34 }.with { !fits && reason == 'inactive or offline' }
        c.targets.find { it.id == 33 }.with { !fits && reason == 'not enough free space' }   // 11 GiB < 10 GiB * 1.2
    }

    def 'a target must keep 10% of itself free after the move'() {
        expect:
        !RetireOps.chooseTarget([[id: 1, freeBytes: 15 * GIB, sizeBytes: 100 * GIB]], 10 * GIB).auto   // 5 GiB left < 10 GiB
        RetireOps.chooseTarget([[id: 1, freeBytes: 25 * GIB, sizeBytes: 100 * GIB]], 10 * GIB).auto.id == 1
    }

    def 'footprint uses what qcow2 allocated, else the full size'() {
        expect:
        RetireOps.footprint([usedBytes: 3338166272, maxBytes: 10737418240]) == 3338166272
        RetireOps.footprint([maxBytes: 10737418240]) == 10737418240
        RetireOps.footprint([:]) == 0
    }

    def 'files on the mount: Morpheus disks, plugin restore leftovers, and anything else'() {
        given:
        List<Map> files = [
                [path: 'test-vm01/hvm_2-disk-0-1b2c3d4', bytes: 3338166272],
                [path: 'test-vm01/hvm_2-disk-0-1b2c3d4.pre-restore-20261020181602', bytes: 1975000000],
                [path: 'isos/ubuntu.iso', bytes: 2000000000],
        ]

        when:
        Map s = RetireOps.classifyFiles(files, ['test-vm01/hvm_2-disk-0-1b2c3d4', null])

        then:
        s.known*.path == ['test-vm01/hvm_2-disk-0-1b2c3d4']
        s.leftovers*.path == ['test-vm01/hvm_2-disk-0-1b2c3d4.pre-restore-20261020181602']
        s.unknown*.path == ['isos/ubuntu.iso']
    }

    def 'Morpheus heartbeat data and VM definition copies are metadata, not unknown files (seen on datastore 30)'() {
        given:
        List<Map> files = [
                [path: 'mvm-hb/cccccccc-3333-4444-8555-666666666666-2/0123456789abcdef0123456789abcdef/test-vm01.xml'],
                [path: 'mvm-hb/cccccccc-3333-4444-8555-666666666666-2/fedcba9876543210fedcba9876543210/hb.properties'],
                [path: 'test-vm01/test-vm01.xml'],
                [path: 'test-vm01/other.xml'],          // not the VM's own definition
                [path: 'nvram/test-vm01_VARS.fd'],      // TPM/UEFI state must never be swept away
        ]

        when:
        Map s = RetireOps.classifyFiles(files, [])

        then:
        s.metadata*.path.size() == 3
        s.unknown*.path == ['test-vm01/other.xml', 'nvram/test-vm01_VARS.fd']
    }

    def 'the plugin\'s restore-point index is metadata and file-restore copies are leftovers'() {
        when:
        Map s = RetireOps.classifyFiles([[path: '.pure-hvm/index/HVM-02.snap1.idx'],
                                         [path: 'vm1/vm1.file-restore-20261021202211-vda.qcow2']], [])

        then:
        s.metadata*.path == ['.pure-hvm/index/HVM-02.snap1.idx']
        s.leftovers*.path == ['vm1/vm1.file-restore-20261021202211-vda.qcow2']
        !s.unknown
    }

    def 'file listing parses paths containing a pipe and is read-only'() {
        given:
        HostRunner.Result r = new HostRunner.Result(0, 'MOUNTED=1\nCOUNT=2\nFILE=a/disk|1024\nFILE=odd|name|7\n')

        expect:
        RetireOps.parseFiles(r) == [[path: 'a/disk', bytes: 1024L], [path: 'odd|name', bytes: 7L]]
        HostScripts.listFiles('/mnt/bbbbbbbb-2222-4333-8444-555555555555').with { s ->
            s.contains('mountpoint -q') && s.contains('-xdev') && s.contains('lost+found') && !(s =~ /\brm\b|umount|mkfs|>\s*\//)
        }
    }

    def 'file listing refuses an unsafe mount path'() {
        when:
        HostScripts.listFiles('/mnt/x; rm -rf /')

        then:
        thrown(IllegalArgumentException)
    }
}
