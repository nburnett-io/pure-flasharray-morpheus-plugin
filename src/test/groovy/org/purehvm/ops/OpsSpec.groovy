package org.purehvm.ops

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.FlashArrayClientSpec.FakeTransport
import org.purehvm.api.HttpTransport
import groovy.json.JsonOutput
import spock.lang.Specification

class OpsSpec extends Specification {
    static final String SERIAL = 'A1B2C3D4E5F6A7B8C9D0E1F2'
    static final String WWID = '3624a9370a1b2c3d4e5f6a7b8c9d0e1f2'
    static final long GIB = 1L << 30

    static HttpTransport.Response ok(Object body) {
        new HttpTransport.Response(status: 200, body: JsonOutput.toJson(body), headers: ['x-auth-token': ['s']])
    }

    FakeTransport fake = new FakeTransport()
    FlashArrayClient fa = new FlashArrayClient('array.example.invalid', 'not-a-real-token', fake)
    List<String> order = []   // interleaved array + host actions, to assert ordering

    def setup() {
        fake.on('GET', '/api/api_version') { u, h, b -> ok([version: ['2.36']]) }
        fake.on('POST', '/login') { u, h, b -> ok([:]) }
        fake.on('GET', '/volumes?destroyed=') { u, h, b -> ok([items: []]) }
        fake.on('POST', '/volumes?names=') { u, h, b -> order << 'array:create'; ok([items: [[name: 'x', serial: SERIAL, provisioned: 100 * GIB]]]) }
        fake.on('PUT', '/volumes/tags/batch') { u, h, b -> ok([items: []]) }
        fake.on('POST', '/connections') { u, h, b -> order << 'array:connect'; ok([items: [[lun: 1]]]) }
        fake.on('GET', '/connections') { u, h, b -> ok([items: [[volume: [name: 'x'], host_group: [name: 'hg']]]]) }
        fake.on('DELETE', '/connections') { u, h, b -> order << 'array:disconnect'; ok([:]) }
        fake.on('PATCH', '/volumes?names=') { u, h, b -> order << (b.contains('destroyed') ? 'array:destroy' : 'array:resize'); ok([items: [[provisioned: 200 * GIB]]]) }
        fake.on('DELETE', '/volumes?names=') { u, h, b -> order << 'array:eradicate'; ok([:]) }
    }

    HostRunner runner(Map<String, Closure<HostRunner.Result>> byScriptMarker) {
        { String host, String script ->
            synchronized (order) {
                def e = byScriptMarker.find { k, v -> script.contains(k) }
                order << "host:${host}:${e?.key ?: '?'}".toString()
                e ? e.value(host) : new HostRunner.Result(0, '')
            }
        } as HostRunner
    }

    def 'provision: create, connect via host group, verify every host, then hand to Morpheus'() {
        given:
        def r = runner(['iscsiadm -m session --rescan': { h -> new HostRunner.Result(0, "SIZE=${100 * GIB}\nPATHS=4") }])
        def registrar = Mock(Gfs2Registrar)
        def ops = new DatastoreOps(fa, r, ['hvm01', 'hvm02', 'hvm03'], registrar)

        when:
        def p = ops.provisionGfs2(7L, 'hg', 'DS01', 100 * GIB)

        then:
        1 * registrar.register(7L, 'DS01', "/dev/mapper/${WWID}") >> 42L
        p.datastoreId == 42L
        p.devicePath == "/dev/mapper/${WWID}"
        order.findAll { it.startsWith('array:') } == ['array:create', 'array:connect']
        order.count { it.contains('rescan') } == 3
    }

    def 'provision rollback: one host cannot see the LUN -> hosts cleaned BEFORE array disconnect, then eradicate'() {
        given:
        def r = runner([
                'CLEAN=1'                    : { h -> new HostRunner.Result(0, 'CLEAN=1') },
                'iscsiadm -m session --rescan': { h -> new HostRunner.Result(0, h == 'hvm03' ? 'SIZE=0\nPATHS=0' : "SIZE=${100 * GIB}\nPATHS=4") },
        ])
        def registrar = Mock(Gfs2Registrar)
        def ops = new DatastoreOps(fa, r, ['hvm01', 'hvm02', 'hvm03'], registrar)

        when:
        ops.provisionGfs2(7L, 'hg', 'DS01', 100 * GIB)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('hvm03: device not visible')
        0 * registrar.register(*_)
        def lastHostClean = order.findLastIndexOf { it.endsWith(':CLEAN=1') }
        lastHostClean >= 0
        lastHostClean < order.indexOf('array:disconnect')
        order.indexOf('array:disconnect') < order.indexOf('array:destroy')
        order.last() == 'array:eradicate'
    }

    def 'grow refuses gfs2_grow until every host reports the new size'() {
        given:
        fake.on('GET', '/volumes?names=DS01') { u, h, b -> ok([items: [[name: 'DS01', serial: SERIAL, provisioned: 100 * GIB]]]) }
        def r = runner(['multipathd resize map': { h -> new HostRunner.Result(0, "SIZE=${h == 'hvm02' ? 100 * GIB : 200 * GIB}\nMOUNT=/mnt/abc") }])
        def ops = new DatastoreOps(fa, r, ['hvm01', 'hvm02'], Mock(Gfs2Registrar))

        when:
        ops.grow(7L, 'DS01', 200 * GIB)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('gfs2_grow NOT run')
        !order.any { it.contains('gfs2_grow') }
    }

    def 'grow runs gfs2_grow exactly once and reports the new filesystem size'() {
        given:
        fake.on('GET', '/volumes?names=DS01') { u, h, b -> ok([items: [[name: 'DS01', serial: SERIAL, provisioned: 100 * GIB]]]) }
        def r = runner([
                'gfs2_grow'            : { h -> new HostRunner.Result(0, "FSSIZE=${200 * GIB}") },
                'multipathd resize map': { h -> new HostRunner.Result(0, "SIZE=${200 * GIB}\nMOUNT=/mnt/abc") },
        ])
        def registrar = Mock(Gfs2Registrar)
        def ops = new DatastoreOps(fa, r, ['hvm01', 'hvm02', 'hvm03'], registrar)

        when:
        def g = ops.grow(7L, 'DS01', 200 * GIB)

        then:
        0 * registrar._      // Morpheus capacity is refreshed by the controller, in-process, not via the registrar
        order.count { it.endsWith(':gfs2_grow') } == 1
        g.fsBytes == 200 * GIB
    }

    def 'recovery session: clone, connect to ONE host, read-only mount, close host-first'() {
        given:
        def r = runner([
                'CLEAN=1'       : { h -> new HostRunner.Result(0, 'CLEAN=1') },
                'lock_nolock'   : { h -> new HostRunner.Result(0, "SIZE=1\nPATHS=4\nSOURCE_LOCKTABLE=hvmclu:ds01\nPR_KEYS=0\nMOUNT=/mnt/pure-recover-abc\nFILE=1024|1700000000.5|/mnt/pure-recover-abc/vm1/disk0.qcow2") },
        ])
        def ops = new RecoveryOps(fa, r)

        when:
        def s = ops.openRecovery('DS01', 'DS01.nightly', 'hvm02', 'hvm02')
        ops.closeRecovery(s)

        then:
        s.files*.path == ['/mnt/pure-recover-abc/vm1/disk0.qcow2']
        def connects = fake.calls.findAll { it.method == 'POST' && it.uri.contains('/connections') }
        connects.size() == 1
        connects[0].uri.contains('host_names=hvm02')
        !connects[0].uri.contains('host_group_names')
        order.findIndexOf { it.endsWith(':CLEAN=1') } < order.indexOf('array:disconnect')
    }

    def 'recovery refuses a snapshot of a different volume'() {
        when:
        new RecoveryOps(fa, runner([:])).openRecovery('DS01', 'DS02.nightly', 'hvm01', 'hvm01')

        then:
        thrown(IllegalArgumentException)
    }

    static RecoveryOps.Session session() {
        new RecoveryOps.Session(tag: 'abc123', host: 'hvm01', mount: '/mnt/pure-recover-abc123', snapshot: 'pg.1634.DS01', sourceVolume: 'DS01')
    }

    def 'VMs in a session: libvirt disks on the datastore, mapped into the snapshot mount'() {
        given:
        def r = runner([
                'virsh domblklist': { h -> new HostRunner.Result(0, "STATE=shut off\nDISK=vda|/mnt/prod/vm1/root.qcow2\nDISK=vdb|/var/morpheus/kvm/local/vm1/other.qcow2\nDISK=vdc|/mnt/prod/vm1/data.qcow2") },
                'EXISTS='         : { h -> new HostRunner.Result(0, 'EXISTS=/mnt/pure-recover-abc123/vm1/root.qcow2') },
        ])

        when:
        def vms = new RecoveryOps(fa, r).vmsInSession(session(), '/mnt/prod', [[serverId: 7L, name: 'vm1', domain: 'vm1', host: 'hvm02', power: 'off']])

        then:
        vms.size() == 1
        vms[0].disks*.target == ['vda', 'vdc']                  // local-datastore disk ignored
        vms[0].disks[0].snapPath == '/mnt/pure-recover-abc123/vm1/root.qcow2'
        !vms[0].restorable                                       // vdc missing from the snapshot
    }

    def 'restore in place does every disk, and rolls back the done ones if one fails'() {
        given:
        int n = 0
        def r = runner([
                'UNDONE=': { h -> new HostRunner.Result(0, 'UNDONE=x') },
                'KEEP='  : { h -> n++ == 0 ? new HostRunner.Result(0, 'KEPT=/mnt/prod/vm1/root.qcow2.pre-restore-1\nSIZE=1') : new HostRunner.Result(4, 'ERROR=convert failed') },
        ])
        def vm = [name: 'vm1', serverId: 7L, domainState: 'shut off', restorable: true, disks: [
                [target: 'vda', path: '/mnt/prod/vm1/root.qcow2', snapPath: '/mnt/pure-recover-abc123/vm1/root.qcow2'],
                [target: 'vdb', path: '/mnt/prod/vm1/data.qcow2', snapPath: '/mnt/pure-recover-abc123/vm1/data.qcow2']]]

        when:
        new RecoveryOps(fa, r).restoreVmInPlace(session(), '/mnt/prod', vm)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('rolled back')
        order.count { it.endsWith(':UNDONE=') } == 1
    }

    def 'restore in place refuses a running VM'() {
        when:
        new RecoveryOps(fa, runner([:])).restoreVmInPlace(session(), '/mnt/prod',
                [name: 'vm1', domainState: 'running', restorable: true, disks: []])

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('powered off')
    }

    def 'VM disk restore script keeps the live disk and refuses VMs with snapshots'() {
        when:
        def s = HostScripts.restoreVmDisk('/mnt/pure-recover-a/vm1/root.qcow2', '/mnt/pure-recover-a', '/mnt/prod', '/mnt/prod/vm1/root.qcow2', '20261020120000')

        then:
        s.contains('KEEP="$DEST.pre-restore-20261020120000"')
        s.contains('VM has snapshots')
        s.contains('mv "$DEST" "$KEEP" && mv "$TMP" "$DEST"')
        !s.contains('rm -f "$DEST"')
    }

    def 'file-restore copies are hot-attached live only, never into the persistent definition'() {
        when:
        def s = HostScripts.fileRestoreAttach('/mnt/pure-recover-a/vm1/root.qcow2', '/mnt/pure-recover-a',
                '/mnt/prod/vm1/vm1.file-restore-20261020120000-vda.qcow2', 'vm1')

        then:
        s.contains('virsh attach-disk "$DOM" "$DEST" "$TGT" --live ')
        !s.contains('--config') && !s.contains('--persistent')
        s.contains('--inactive')   // a slot used only in the persistent definition is not free either
        s.contains('snapconvert "$SRC" "$TMP"')
    }

    def 'file-restore scripts only touch file-restore copies of that VM'() {
        when:
        HostScripts.fileRestoreAttach('/mnt/pure-recover-a/vm1/root.qcow2', '/mnt/pure-recover-a', '/mnt/prod/vm1/root.qcow2', 'vm1')

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.fileRestoreRemove('/mnt/prod/vm1/root.qcow2', 'vm1', '/mnt/prod')

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.fileRestoreRemove('/mnt/prod/vm2/vm2.file-restore-20261020120000-vda.qcow2', 'vm1', '/mnt/prod')

        then:
        thrown(IllegalArgumentException)

        when:
        def ok = HostScripts.fileRestoreRemove('/mnt/prod/vm1/vm1.file-restore-20261020120000-vda.qcow2', 'vm1', '/mnt/prod')

        then:
        ok.contains('detach-disk "$DOM" "$T" --live')
    }

    def 'restore-point index and network scripts'() {
        when:
        def info = HostScripts.restoreTabInfo('/mnt/m1/vm1', 'vm1', '/mnt/m1', ['vm1/d0', 'vm1/d1'])
        def w = HostScripts.writeSnapshotIndex('/mnt/m1', 'HVM-02.snap1.idx', ['vm1/d0', 'vm1/d1'])
        def down = HostScripts.setLinks('vm1', false)

        then:
        info.contains("grep -Fxq 'vm1/d0'") && info.contains('NIC=') && info.contains('COPY=')
        // the list travels base64-encoded: no file name from a snapshot can become script text
        w.contains("echo '${'vm1/d0\nvm1/d1\n'.bytes.encodeBase64()}' | base64 -d") && !w.contains('vm1/d0') && w.contains('mv -f')
        down.contains('domif-setlink "$DOMN" "$m" down --config')

        when:
        HostScripts.writeSnapshotIndex('/mnt/m1', 'x.idx', ['../etc/passwd'])

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.writeSnapshotIndex('/mnt/m1', 'x.idx', ["vm1/d0\nPURE_HVM_INDEX_EOF\nrm -rf /"])

        then:
        thrown(IllegalArgumentException)
    }

    def 'the sweeper only removes a copy on the VM\'s own host, and keeps attached copies whose time is not up'() {
        given:
        String f = '/mnt/prod/vm1/vm1.file-restore-20261020120000-vda.qcow2'

        when:
        def user = HostScripts.fileRestoreRemove(f, 'vm1', '/mnt/prod')
        def expired = HostScripts.fileRestoreRemove(f, 'vm1', '/mnt/prod', HostScripts.RemoveMode.REAP_EXPIRED)
        def running = HostScripts.fileRestoreRemove(f, 'vm1', '/mnt/prod', HostScripts.RemoveMode.REAP_IF_DETACHED)
        def orphan = HostScripts.fileRestoreRemove(f, 'vm1', '/mnt/prod', HostScripts.RemoveMode.ORPHAN)

        then:
        !user.contains('SKIP=') && !user.contains('KEEP=')
        expired.contains('SKIP=domain not on this host') && !expired.contains('KEEP=')
        running.contains('KEEP=attached') && running.contains('SKIP=domain not on this host')
        orphan.contains('SKIP=domain still defined here')
        [user, expired, running, orphan].every { it.contains("/mnt/prod/.pure-hvm/copies/") && it.contains('rm -f "$TM"') }

        when:
        HostScripts.fileRestoreRemove(f, 'vm1', '/mnt/other')

        then:
        thrown(IllegalArgumentException)
    }

    def 'copy timers and the sweep listing'() {
        expect:
        HostScripts.fileRestoreTimer('/mnt/prod', 'vm1.file-restore-20261020120000-vda.qcow2', 1792700000000L).contains('echo 1792700000000 >')
        HostScripts.fileRestoreSweepList().with { it.contains('findmnt -rn -t gfs2') && it.contains('/mnt/pure-recover-*) continue') }

        when:
        HostScripts.fileRestoreTimer('/mnt/prod', '../../etc/passwd', 1L)

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.removeStaleTimers(['/mnt/prod/vm1/root.qcow2'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'a disk is only replaced and dropped when the replaced file never held data'() {
        when:
        def s = HostScripts.removeKeptIfBlank('/mnt/prod/vm1/d1', '/mnt/prod/vm1/d1.pre-restore-20261020120000')

        then:
        s.contains('"data": true')
        s.contains('KEPT_DATA=')

        when:
        HostScripts.removeKeptIfBlank('/mnt/prod/vm1/d1', '/mnt/prod/vm1/other')

        then:
        thrown(IllegalArgumentException)
    }

    def 'host scripts reject unsafe input'() {
        when:
        HostScripts.hostCleanup(WWID, "/mnt/x; rm -rf /")

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.rescan('36000c29abc')

        then:
        thrown(IllegalArgumentException)

        when:
        HostScripts.restoreDisk('/etc/shadow', '/mnt/pure-recover-a', '/mnt/prod', '/mnt/prod/restored/x.qcow2')

        then:
        thrown(IllegalArgumentException)
    }

    def 'recovery mount uses lock_nolock with a private lock table and never touches DLM'() {
        when:
        def s = HostScripts.recoveryMount(WWID, 'abc123')

        then:
        s.contains('lockproto=lock_nolock,locktable=recover:abc123')
        s.contains('mount -o remount,ro')
        !s.contains('lock_dlm')
    }

    def 'resignature verifies the lock table actually changed'() {
        when:
        def s = HostScripts.resignature(WWID, 'ds01_restore')

        then:
        s.contains('tunegfs2 -o locktable=')
        s.contains('tunegfs2 -L')
        s.contains('lock table did not change')
        s.contains('tunegfs2 -U')
        s.contains('mvm-hb.from-snapshot')
    }
}
