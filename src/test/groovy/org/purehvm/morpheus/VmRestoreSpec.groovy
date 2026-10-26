package org.purehvm.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousInstanceService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import spock.lang.Specification

/** Shapes: Acme (7, customer) owns acme-vm01 (instance 6, server 53) on datastore 30 of cluster 15 (owner Cloud 2). */
class VmRestoreSpec extends Specification {
    static final Account CLOUD = new Account(id: 2, name: 'Cloud', masterAccount: false)
    static final Account ACME = new Account(id: 7, name: 'Acme', masterAccount: false)
    static final Account GLOBEX = new Account(id: 4, name: 'Globex', masterAccount: false)

    VmRestore restore

    def setup() {
        Instance inst = new Instance(id: 6, name: 'acme-vm01', account: ACME, containers: [new Workload(server: new ComputeServer(id: 53))])
        ComputeServer vm = new ComputeServer(id: 53, name: 'acme-vm01', account: ACME,
                volumes: [new StorageVolume(id: 110, name: 'root', datastore: new Datastore(id: 30))])
        ComputeServerGroup cluster = new ComputeServerGroup(id: 15, name: 'cluster-a-old', owner: CLOUD)
        def instances = Stub(MorpheusSynchronousInstanceService) { get(6L) >> inst }
        def servers = Stub(MorpheusSynchronousComputeServerService) { get(53L) >> vm; list(_) >> [] }
        def datastores = Stub(MorpheusSynchronousDatastoreService) { get(30L) >> new Datastore(id: 30, name: 'array01-ds01', refType: 'ComputeServerGroup', refId: 15) }
        def cloud = Stub(MorpheusSynchronousCloudService) { getDatastore() >> datastores }
        def clusters = Stub(MorpheusSynchronousComputeServerGroupService) { get(15L) >> cluster }
        def services = Stub(MorpheusServices) {
            getInstance() >> instances; getComputeServer() >> servers; getCloud() >> cloud; getCluster() >> clusters
        }
        restore = new VmRestore(Stub(MorpheusContext) { getServices() >> services })
    }

    def 'another customer tenant cannot restore the VM, and is refused before any storage call'() {
        when:
        restore.resolve(6L, new User(username: 'mc-admin', account: GLOBEX), [])

        then:   // and is not told the VM's name
        def e = thrown(SecurityException)
        e.message.contains('does not belong to your tenant')
        !e.message.contains('acme')
    }

    def 'Morpheus\'s records of hot-attached file-restore copies are never taken for the VM\'s disks'() {
        expect:
        VmRestore.isCopyRecord(new StorageVolume(name: 'acme-vm01/acme-vm01.file-restore-20261021202211-vda.qcow2'))
        VmRestore.isCopyRecord(new StorageVolume(name: 'x', externalId: 'acme-vm01/acme-vm01.file-restore-20261021202211-vda.qcow2'))
        !VmRestore.isCopyRecord(new StorageVolume(name: 'root', externalId: 'acme-vm01/hvm_3-disk-0-0a1b2c3'))
    }

    def 'the VM\'s own tenant gets past the tenancy check (and on to the storage checks), told nothing about the storage'() {
        when:
        restore.resolve(6L, new User(username: 'acme-admin', account: ACME), [])

        then:   // no FlashArray link in this stub: refused for storage, not for tenancy
        def e = thrown(VmRestore.PublicError)
        e.message == 'acme-vm01 is not on storage with restore points'
        !e.message.contains('array01')
    }

    def 'the operator tier (cluster owner) may restore any VM on its cluster'() {
        when:
        restore.resolve(6L, new User(username: 'ops-user', account: CLOUD), [])

        then:
        def e = thrown(IllegalStateException)
        !(e instanceof SecurityException)
    }

    def 'restore-point ids are opaque, stable, and specific to the VM'() {
        expect:
        String a = VmRestore.pointId(6L, 'array01-ds01.acmetest')
        a ==~ /[0-9a-f]{24}/
        a == VmRestore.pointId(6L, 'array01-ds01.acmetest')
        a != VmRestore.pointId(7L, 'array01-ds01.acmetest')
        a != VmRestore.pointId(6L, 'pgroup-auto.1645.array01-ds01')
    }

    def 'what the tab is shown about a VM names its disks, not where they live'() {
        given:
        def t = new VmRestore.Target(instance: new Instance(id: 6, name: 'acme-vm01'), server: new ComputeServer(id: 53),
                datastore: new Datastore(id: 30, name: 'array01-ds01'), hostName: 'hvm-node01',
                disks: [new StorageVolume(id: 113, name: 'root', deviceDisplayName: 'vda', maxStorage: 10737418240L, rootVolume: true,
                        volumePath: '/mnt/aaaaaaaa/acme-vm01/hvm_3-disk-0-0a1b2c3')])

        when:
        String view = t.toView().toString()

        then:
        view.contains('root') && view.contains('vda')
        !view.contains('array01') && !view.contains('/mnt') && !view.contains('datastore')
    }

    def 'customers see their own messages, and a generic one with a reference for anything else'() {
        expect:
        VmRestore.publicMessage(new VmRestore.PublicError('acme-vm01 must be stopped first')) == 'acme-vm01 must be stopped first'
        VmRestore.publicMessage(new SecurityException('acme-vm01 does not belong to your tenant')).contains('tenant')
        String g = VmRestore.publicMessage(new IllegalStateException('array01-ds01 is not mounted on hvm-node01'), 'ab12cd34')
        !g.contains('array01') && g.contains('ab12cd34')
    }

    def 'restore points are filtered by what each snapshot holds, and unindexed ones by the VM\'s creation time'() {
        given:
        long born = 1_792_000_000_000L
        def t = new VmRestore.Target(instance: new Instance(id: 6, name: 'acme-vm01'), server: new ComputeServer(id: 53, dateCreated: new Date(born)),
                disks: [new StorageVolume(id: 113, name: 'root', externalId: 'acme-vm01/hvm_3-disk-0-0a1b2c3'),
                        new StorageVolume(id: 114, name: 'data', volumePath: '/mnt/aaaaaaaa/acme-vm01/hvm_3-disk-1-aa')])
        List<Map> snaps = [
                [name: 'HVM-02.full', created: born + 50],
                [name: 'pg.1.HVM-02', created: born + 40, protectionGroup: 'pg'],
                [name: 'HVM-02.none', created: born + 30],
                [name: 'HVM-02.unindexed', created: born + 20],
                [name: 'HVM-02.before-vm', created: born - 10]]
        Map info = [indexed: ['HVM-02.full.idx', 'pg.1.HVM-02.idx', 'HVM-02.none.idx'] as Set,
                    has: ['HVM-02.full.idx': ['acme-vm01/hvm_3-disk-0-0a1b2c3', 'acme-vm01/hvm_3-disk-1-aa'] as Set,
                          'pg.1.HVM-02.idx': ['acme-vm01/hvm_3-disk-0-0a1b2c3'] as Set].withDefault { [] as Set }]

        when:
        List<Map> p = VmRestore.pointsFrom(t, snaps, info)

        then:
        p*.coverage == ['full', 'partial', 'unverified']
        p[1].disks == [113L] && p[1].kind == 'scheduled'
        p*.id.every { it ==~ /[0-9a-f]{24}/ }
        !p.toString().contains('HVM-02')
    }

    def 'network state of a restored clone'() {
        expect:
        VmRestore.networkState([[mac: 'a', link: 'down', live: null]]) == 'disconnected'
        VmRestore.networkState([[mac: 'a', link: 'down', live: 'up']]) == 'connected'
        VmRestore.networkState([[mac: 'a', link: 'up'], [mac: 'b', link: 'down']]) == 'partly connected'
        VmRestore.networkState([]) == null
    }

    def 'snapshot index names and mounts'() {
        expect:
        SnapshotIndex.fileName('array2:pg.1.HVM-02') == 'array2_pg.1.HVM-02.idx'
        SnapshotIndex.mountOf('/mnt/aaaaaaaa-1111/acme-vm01/hvm_3-disk-0') == '/mnt/aaaaaaaa-1111'
        SnapshotIndex.mountOf('relative/path') == null
    }

    def 'file-recovery copy lifetimes: default 24 h, never beyond the maximum (72 h)'() {
        expect:
        FileRestoreCopies.clamp(null, [:]) == 24
        FileRestoreCopies.clamp('48', [:]) == 48
        FileRestoreCopies.clamp('500', [:]) == 72
        FileRestoreCopies.clamp('0', [:]) == 1
        FileRestoreCopies.clamp('abc', [fileRestoreHours: '8']) == 8
        FileRestoreCopies.clamp('72', [fileRestoreMaxHours: '36']) == 36
        FileRestoreCopies.defaultHours([fileRestoreHours: '100', fileRestoreMaxHours: '72']) == 72
    }

    def 'the sweeper drops only the removed copy\'s Morpheus record, not another copy\'s'() {
        given:
        def services = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        def volumes = Mock(com.morpheusdata.core.MorpheusStorageVolumeService)
        def ctx = Stub(MorpheusContext) { getAsync() >> services }
        services.getStorageVolume() >> volumes
        def a = new StorageVolume(id: 1, name: 'vm1/vm1.file-restore-20261021202211-vda.qcow2')
        def b = new StorageVolume(id: 2, name: 'vm1/vm1.file-restore-20261021120000-vda.qcow2')
        def server = new ComputeServer(id: 53, name: 'vm1', volumes: [new StorageVolume(id: 113, name: 'root'), a, b])
        List removed = null

        when:
        VmRestore.dropCopyRecords(ctx, server, [], 'vm1.file-restore-20261021202211-vda.qcow2')

        then:
        1 * volumes.remove(_, server, false) >> { args -> removed = args[0]; io.reactivex.rxjava3.core.Single.just(true) }
        removed*.id == [1L]
    }

    def 'a copy without a timer expires its default lifetime after its file time'() {
        expect:
        FileRestoreCopies.expiresOf('1792700000000', '1', 24) == 1792700000000L
        FileRestoreCopies.expiresOf(null, '1792700000', 24) == 1792700000000L + 24 * 3600_000L
        FileRestoreCopies.expiresOf(null, null, 24) == null
        FileRestoreCopies.domainOf('/mnt/u/acme-vm01/acme-vm01.file-restore-20261021202211-vda.qcow2') == 'acme-vm01'
    }

    def 'a disk id that is not one of the VM\'s disks is refused'() {
        given:
        def t = new VmRestore.Target(instance: new Instance(id: 6, name: 'acme-vm01'), disks: [new StorageVolume(id: 113, name: 'root')])

        when:
        t.disk(999L)

        then:
        thrown(VmRestore.PublicError)
    }
}
