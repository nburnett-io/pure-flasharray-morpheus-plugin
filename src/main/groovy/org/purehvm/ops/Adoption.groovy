package org.purehvm.ops

import org.purehvm.api.FlashArrayClient
import org.purehvm.api.PureNames

/**
 * Adopts GFS2 datastores that were created before the plugin (or outside it) so grow, snapshots and recovery work
 * on them. Adoption only writes the plugin's tags on the array volume; nothing on the array, hosts or Morpheus
 * changes otherwise.
 *
 * Matching is exact: a Morpheus GFS2 datastore records its LUN as config.blockDevice = /dev/mapper/3624a9370<serial>
 * (seen on 9.1: type code libvirt-dir-gfs2), and the volume with that serial on the linked array is its backing LUN.
 */
class Adoption {

    /** Morpheus side of a datastore, as gathered by the controller. */
    static class DatastoreRef {
        Long id
        String name
        String typeCode
        String blockDevice
        String mountPath
        Boolean active
    }

    static boolean isGfs2(DatastoreRef d) {
        (d.typeCode ?: '').toLowerCase().contains('gfs2') || d.blockDevice?.startsWith('/dev/mapper/3')
    }

    /**
     * @return one row per GFS2 datastore: [datastoreId, name, blockDevice, serial, volume, provisioned, state, detail]
     *         state: adopt | adopted | elsewhere | not-on-array | not-pure
     */
    static List<Map> scan(FlashArrayClient fa, Long clusterId, String hostGroup, List<DatastoreRef> datastores) {
        List<Map> rows = []
        datastores.findAll { isGfs2(it) }.each { DatastoreRef d ->
            Map row = [datastoreId: d.id, name: d.name, blockDevice: d.blockDevice, mountPath: d.mountPath]
            String serial = PureNames.serialFromWwid(d.blockDevice)
            if (!serial) {
                rows << row + [state: 'not-pure', detail: 'block device is not a FlashArray LUN']
                return
            }
            row.serial = serial
            Map vol = fa.volumeBySerial(serial)
            if (!vol) {
                rows << row + [state: 'not-on-array', detail: 'no volume with this serial on the linked array']
                return
            }
            row.volume = vol.name
            row.provisioned = vol.provisioned
            Map<String, String> tags = fa.volumeTags(vol.name as String)
            if (PureNames.ownedBy(tags, clusterId)) {
                rows << row + [state: 'adopted', detail: tags['adopted'] ? "adopted ${tags['adopted']}".toString() : 'created by the plugin']
                return
            }
            if (tags[PureNames.TAG_CLUSTER]) {
                rows << row + [state: 'elsewhere', detail: "tagged for cluster ${tags[PureNames.TAG_CLUSTER]}; not adopted".toString()]
                return
            }
            List<Map> conns = fa.listConnections([volume_names: vol.name])
            List<String> groups = conns*.hostGroup.findAll().unique()
            String detail = hostGroup && groups.contains(hostGroup) ? "connected via host group ${hostGroup}".toString()
                    : (groups ? "connected via host group(s) ${groups.join(', ')}, not the cluster's (${hostGroup ?: 'none set'})".toString()
                    : 'connected to individual hosts only (no host group)')
            rows << row + [state: 'adopt', detail: detail]
        }
        rows
    }

    private static final List<String> RANK = ['adopted', 'adopt', 'elsewhere', 'not-on-array', 'not-pure']

    /**
     * Merges per-array scans (storageServerId -> rows) for a cluster linked to several arrays. A LUN serial is unique
     * across arrays, so a datastore is found on at most one; its best row wins and carries that storageServerId.
     */
    static List<Map> merge(Map<Long, List<Map>> bySid) {
        Map<Object, Map> best = [:]
        bySid.each { Long sid, List<Map> rows ->
            rows.each { Map r ->
                Map cur = best[r.datastoreId]
                if (cur == null || RANK.indexOf(r.state as String) < RANK.indexOf(cur.state as String)) {
                    best[r.datastoreId] = r + [storageServerId: r.state in ['not-on-array', 'not-pure'] ? null : sid]
                }
            }
        }
        best.values().collect { Map r ->
            r.state == 'not-on-array' && bySid.size() > 1 ? r + [detail: 'no volume with this serial on any linked array'] : r
        }
    }

    /** Tags one scanned row's volume as this cluster's datastore. Refuses anything not in state 'adopt'. */
    static void adopt(FlashArrayClient fa, Long clusterId, Map row) {
        if (row.state != 'adopt') {
            throw new IllegalStateException("${row.name}: ${row.state} (${row.detail}) - not adoptable")
        }
        fa.tagVolume(row.volume as String, PureNames.tagNamespace, [
                'morpheus-cluster-id'  : clusterId as String,
                'morpheus-datastore-id': row.datastoreId as String,
                'datastore-name'       : row.name as String,
                'role'                 : 'gfs2-datastore',
                'adopted'              : new Date().toInstant().toString(),
        ])
    }
}
