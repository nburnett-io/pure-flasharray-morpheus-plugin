package org.purehvm.ops

/**
 * Hands a prepared block device to Morpheus' BUILT-IN GFS2 datastore type, which formats it (never reformats one that
 * already holds GFS2: both implementations send allowReformat=false),
 * joins DLM/Corosync, mounts on every host at /mnt/<uuid>, creates the libvirt pools and owns HA heartbeat.
 * The plugin deliberately does not do any of that itself (see OVERVIEW.md, "Why the plugin doesn't mkfs").
 */
interface Gfs2Registrar {
    /**
     * @param clusterId Morpheus cluster (ComputeServerGroup) id
     * @param devicePath /dev/mapper/3624a9370<serial>, present on every host
     * @return the new Morpheus datastore id
     */
    Long register(Long clusterId, String name, String devicePath)
}
