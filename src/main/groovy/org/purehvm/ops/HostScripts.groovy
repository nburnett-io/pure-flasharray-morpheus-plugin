package org.purehvm.ops

/**
 * Bash run on HVM hosts (as root via the Morpheus agent). Each script prints KEY=value lines that
 * {@link HostRunner.Result#kv} parses, and exits non-zero on a precondition failure.
 *
 * Sources for the sequences: docs/research/07-gfs2-clone-and-linux-best-practice.md (A3 workflows i-iii),
 * Everpure HPE VME iSCSI quick start (rescan before the LUN is usable), Morpheus 9.1 storage lifecycle
 * (GFS2 grow: one host, after every host sees the new size).
 */
class HostScripts {

    static void requireWwid(String w) {
        if (!(w ==~ /3624a9370[0-9a-f]{24}/)) {
            throw new IllegalArgumentException("Not a FlashArray WWID: ${w}")
        }
    }

    static void requireSafePath(String p) {
        if (!(p ==~ /\/[A-Za-z0-9._\/-]+/) || p.contains('..')) {
            throw new IllegalArgumentException("Unsafe path: ${p}")
        }
    }

    private static void requireDomain(String domain) {
        if (!(domain ==~ /[A-Za-z0-9._:-]{1,128}/)) {
            throw new IllegalArgumentException("Unsafe domain name: ${domain}")
        }
    }

    private static void requirePoolName(String name) {
        if (name && !(name ==~ /[A-Za-z0-9._-]+/)) {
            throw new IllegalArgumentException("Unsafe pool name: ${name}")
        }
    }

    /** A restore's kept file (<dest>.pre-restore-<ts>) for exactly this disk. */
    private static void requireKept(String dest, String kept) {
        [dest, kept].each { requireSafePath(it) }
        if (!kept.startsWith(dest + '.pre-restore-')) {
            throw new IllegalArgumentException('kept file does not belong to this disk')
        }
    }

    static final String RECOVERY_PREFIX = '/mnt/pure-recover-'

    private static void requireRecoveryMount(String mountPoint) {
        requireSafePath(mountPoint)
        if (!mountPoint.startsWith(RECOVERY_PREFIX)) {
            throw new IllegalArgumentException("Not a recovery mount: ${mountPoint}")
        }
    }

    static final String FILE_RESTORE_TAG = 'file-restore'
    /** End of a file-restore copy's name: <domain>.file-restore-<UTC yyyyMMddHHmmss>-<device>.qcow2 */
    static final String COPY_SUFFIX = /\.${FILE_RESTORE_TAG}-[0-9]{14}-[a-z]+\.qcow2/

    /**
     * Shell helpers every script gets. mnt_of: where a map is mounted; vsize: a qcow2's virtual size. No helper pipes
     * big output into an early-exiting reader: under pipefail that SIGPIPEs the writer and fails the pipeline.
     */
    private static final String HEADER = '''set -uo pipefail
export LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin
lt() { tunegfs2 -l "$1" 2>/dev/null | sed -n 's/^[Ll]ock [Tt]able[^:]*: *//p' | head -1; }
paths_of() { multipathd show paths raw format '%w %d' 2>/dev/null | awk -v w="$1" '$1==w {print $2}'; }
healthy_paths() { multipathd show paths raw format '%w %t %T' 2>/dev/null | awk -v w="$1" '$1==w && $2=="active" && $3=="ready"' | wc -l; }
mnt_of() { findmnt -rn -S "/dev/mapper/$1" -o TARGET | head -1; }
vsize() { qemu-img info -U --output=json "$1" | sed -n 's/.*"virtual-size": *\\([0-9]*\\).*/\\1/p' | head -1; }
'''

    /** Disk files on a recovery mount (size > 1 MiB, no heartbeat data, no restore leftovers): FILE=<bytes>|<mtime>|<path>. */
    private static final String RECOVERY_FILES =
            '''find "$MP" -maxdepth 5 -type f -size +1M -not -path '*/mvm-hb/*' -not -name '*.pre-restore-*' -printf 'FILE=%s|%T@|%p\\n'
'''

    /**
     * Removes FlashArray maps whose paths are ALL dead and that are neither mounted nor open, with their SCSI devices.
     * Why: a LUN the host lost (unmapped on the array) leaves a dead device at its SCSI address; a new LUN later given
     * the same LUN ID (Purity counts host-group LUNs down from 254) is never discovered by "session --rescan" because
     * the address is already taken. Seen on a rebuilt host: two dead maps at LUN 254/253 hid every new LUN.
     */
    static final String CLEAN_STALE = '''for SW in $(multipathd show maps raw format '%w' 2>/dev/null | grep '^3624a937'); do
  [ "$(healthy_paths $SW)" = "0" ] || continue
  findmnt -rn -S /dev/mapper/$SW >/dev/null && { echo "WARN=all paths dead but mounted: $SW (left alone)"; continue; }
  SO=$(dmsetup info -c --noheadings -o open $SW 2>/dev/null | tr -d ' ')
  [ "${SO:-0}" = "0" ] || { echo "WARN=all paths dead but open: $SW (left alone)"; continue; }
  SP=$(paths_of $SW)
  multipath -f $SW >/dev/null 2>&1 || { echo "WARN=could not flush $SW"; continue; }
  for d in $SP; do echo 1 > /sys/block/$d/device/delete 2>/dev/null || true; done
  echo "STALE_REMOVED=$SW"
done
'''

    /** Rescan existing sessions and wait (up to 30 s) for the multipath map of one WWID. */
    static String rescan(String wwid) {
        requireWwid(wwid)
        HEADER + CLEAN_STALE + """W=${wwid}
iscsiadm -m session --rescan >/dev/null 2>&1 || true
udevadm settle -t 30 >/dev/null 2>&1 || true
for i in \$(seq 1 30); do [ -e /dev/mapper/\$W ] && break; multipath \$W >/dev/null 2>&1 || true; sleep 1; done
if [ -e /dev/mapper/\$W ]; then
  echo "SIZE=\$(blockdev --getsize64 /dev/mapper/\$W)"
  echo "PATHS=\$(healthy_paths \$W)"
else
  echo "SIZE=0"; echo "PATHS=0"
fi
"""
    }

    /** After an array-side grow: rescan each path, resize the map, report the new size. */
    static String resize(String wwid) {
        requireWwid(wwid)
        HEADER + """W=${wwid}
[ -e /dev/mapper/\$W ] || { echo "ERROR=no device"; exit 2; }
for d in \$(paths_of \$W); do echo 1 > /sys/block/\$d/device/rescan; done
udevadm settle -t 30 >/dev/null 2>&1 || true
multipathd resize map \$W >/dev/null
echo "SIZE=\$(blockdev --getsize64 /dev/mapper/\$W)"
echo "MOUNT=\$(mnt_of \$W)"
"""
    }

    /** gfs2_grow runs on ONE host, on the mount point (never the raw device). */
    static String gfs2Grow(String mountPoint) {
        requireSafePath(mountPoint)
        HEADER + """MP=${mountPoint}
mountpoint -q "\$MP" || { echo "ERROR=not mounted"; exit 2; }
gfs2_grow "\$MP" >/dev/null || { echo "ERROR=gfs2_grow failed"; exit 3; }
echo "FSSIZE=\$(df -B1 --output=size "\$MP" | tail -1 | tr -d ' ')"
"""
    }

    /**
     * Single-host, lock_nolock, read-only "recovery mount" of a snapshot clone (workflow i).
     * A distinct locktable= avoids the /sys/fs/gfs2/<table> clash with production; lock_nolock keeps the
     * clone out of the cluster's DLM. The rw mount replays the dirty journals; then it is remounted ro.
     */
    static String recoveryMount(String wwid, String tag) {
        requireWwid(wwid)
        if (!(tag ==~ /[a-z0-9]{1,16}/)) {
            throw new IllegalArgumentException("bad tag ${tag}")
        }
        rescan(wwid) + """MP=/mnt/pure-recover-${tag}
[ -e /dev/mapper/\$W ] || { echo "ERROR=device did not appear"; exit 2; }
T=\$(blkid -p -o value -s TYPE /dev/mapper/\$W 2>/dev/null)
[ "\$T" = gfs2 ] || { echo "ERROR=not gfs2 (\$T)"; exit 3; }
echo "SOURCE_LOCKTABLE=\$(lt /dev/mapper/\$W)"
if command -v sg_persist >/dev/null; then echo "PR_KEYS=\$(sg_persist -i -k /dev/mapper/\$W 2>/dev/null | grep -c '^ *0x')"; fi
findmnt -rn -S /dev/mapper/\$W >/dev/null && { echo "ERROR=already mounted"; exit 4; }
mkdir -p "\$MP"
mount -t gfs2 -o lockproto=lock_nolock,locktable=recover:${tag},noatime /dev/mapper/\$W "\$MP" || { echo "ERROR=mount failed"; exit 5; }
mount -o remount,ro "\$MP"
echo "MOUNT=\$MP"
""" + RECOVERY_FILES
    }

    /**
     * Copy one disk out of a recovery mount onto a production datastore as a NEW file (never overwrites), through
     * snapconvert: snapshot-time data only, one backing level flattened, refused unless qemu-img check passes.
     */
    static String restoreDisk(String source, String recoveryMount, String prodMount, String dest) {
        [source, recoveryMount, prodMount, dest].each { requireSafePath(it) }
        if (!source.startsWith(recoveryMount + '/') || !dest.startsWith(prodMount + '/')) {
            throw new IllegalArgumentException('source must be inside the recovery mount and dest inside the production mount')
        }
        HEADER + SNAP_CONVERT + """SRC='${source}'; RMP='${recoveryMount}'; PMP='${prodMount}'; DEST='${dest}'
[ -e "\$DEST" ] && { echo "ERROR=destination exists"; exit 2; }
mountpoint -q "\$PMP" || { echo "ERROR=production datastore not mounted"; exit 3; }
mkdir -p "\$(dirname "\$DEST")"
snapconvert "\$SRC" "\$DEST.part"
mv "\$DEST.part" "\$DEST"
chown libvirt-qemu:kvm "\$DEST" 2>/dev/null || true
echo "SIZE=\$(stat -c %s "\$DEST")"
echo "DEST=\$DEST"
"""
    }

    /** Rescan every iSCSI session and let multipath create maps for new LUNs (does not reload existing maps). */
    static String rescanAll() {
        HEADER + CLEAN_STALE + '''iscsiadm -m session --rescan >/dev/null 2>&1 || echo "WARN=no iSCSI sessions to rescan"
udevadm settle -t 30 >/dev/null 2>&1 || true
multipath >/dev/null 2>&1 || true
echo "SESSIONS=$(iscsiadm -m session 2>/dev/null | grep -c purestorage)"
echo "PURE_MAPS=$(multipathd show maps raw format '%w' 2>/dev/null | grep -c '^3624a937')"
'''
    }

    /** For each WWID: STORAGE=<wwid>|<present 0/1>|<mount target or empty>. */
    static String storageState(List<String> wwids) {
        wwids.each { requireWwid(it) }
        HEADER + wwids.collect { String w ->
            "echo \"STORAGE=${w}|\$([ -e /dev/mapper/${w} ] && echo 1 || echo 0)|\$(mnt_of ${w})\""
        }.join('\n') + '\n'
    }

    /**
     * Read from a host that already mounts the datastore: its mount options, the libvirt pool whose target is the
     * mount point (name + base64 XML), and the corosync cluster name.
     */
    static String mountReference(String wwid, String mountPoint) {
        requireWwid(wwid)
        requireSafePath(mountPoint)
        HEADER + """MP='${mountPoint}'
findmnt -rn -S /dev/mapper/${wwid} -T "\$MP" >/dev/null 2>&1 || { echo "ERROR=not mounted at \$MP on this host"; exit 2; }
echo "OPTS=\$(findmnt -rn -S /dev/mapper/${wwid} -o OPTIONS | head -1)"
for p in \$(virsh pool-list --all --name 2>/dev/null); do
  if [ "\$(virsh pool-dumpxml "\$p" 2>/dev/null | sed -n 's:.*<path>\\(.*\\)</path>.*:\\1:p' | head -1)" = "\$MP" ]; then
    echo "POOL=\$p"; echo "POOLXML=\$(virsh pool-dumpxml "\$p" | base64 -w0)"; break
  fi
done
echo "CLUSTER=\$(corosync-cmapctl -g totem.cluster_name 2>/dev/null | sed 's/.*= *//')"
"""
    }

    /**
     * Mount a cluster GFS2 datastore on a host that joined later, exactly as on the reference host: same mount point,
     * same options (minus per-mount runtime keys), same libvirt pool. Refuses unless this host is in the same corosync
     * cluster as the lock table and DLM is running, so it can never join a different lockspace.
     */
    static String mountGfs2(String wwid, String mountPoint, String options, String poolName, String poolXmlB64) {
        requireWwid(wwid)
        requireSafePath(mountPoint)
        if (options && !(options ==~ /[A-Za-z0-9_=,.:-]+/)) {
            throw new IllegalArgumentException("unsafe mount options ${options}")
        }
        requirePoolName(poolName)
        if (poolXmlB64 && !(poolXmlB64 ==~ /[A-Za-z0-9+\/=]+/)) {
            throw new IllegalArgumentException('pool XML must be base64')
        }
        // keep only options that are safe to repeat (drop rw/ro and per-mount runtime values like localflocks/jid/hostdata)
        String opts = (options ?: 'noatime').tokenize(',').findAll { String o -> !(o in ['rw', 'ro', 'relatime']) && !o.startsWith('jid=') && !o.startsWith('hostdata=') }.join(',') ?: 'noatime'
        HEADER + """W=${wwid}; MP='${mountPoint}'
[ -e /dev/mapper/\$W ] || { echo "ERROR=LUN not visible on this host (add it to the host group and rescan)"; exit 2; }
if findmnt -rn -S /dev/mapper/\$W >/dev/null; then echo "MOUNTED=\$(mnt_of \$W)"; echo "ALREADY=1"; exit 0; fi
T=\$(lt /dev/mapper/\$W); TC=\${T%%:*}
CC=\$(corosync-cmapctl -g totem.cluster_name 2>/dev/null | sed 's/.*= *//')
[ -n "\$CC" ] || { echo "ERROR=corosync not running on this host - Morpheus has not finished joining it to the cluster"; exit 3; }
[ "\$TC" = "\$CC" ] || { echo "ERROR=lock table cluster '\$TC' does not match this host's corosync cluster '\$CC'"; exit 4; }
dlm_tool ls >/dev/null 2>&1 || { echo "ERROR=DLM not running on this host"; exit 5; }
mkdir -p "\$MP"
mount -t gfs2 -o ${opts} /dev/mapper/\$W "\$MP" || { echo "ERROR=mount failed (check journals: gfs2_jadd on a mounted host if the cluster outgrew them)"; exit 6; }
echo "MOUNTED=\$MP"
${poolName ? """if ! virsh pool-info '${poolName}' >/dev/null 2>&1; then
  echo '${poolXmlB64}' | base64 -d > /tmp/pure-pool-\$\$.xml && virsh pool-define /tmp/pure-pool-\$\$.xml >/dev/null; rm -f /tmp/pure-pool-\$\$.xml
fi
virsh pool-start '${poolName}' >/dev/null 2>&1 || true
virsh pool-autostart '${poolName}' >/dev/null 2>&1 || true
echo "POOL=\$(virsh pool-info '${poolName}' 2>/dev/null | sed -n 's/^State: *//p')"
""" : 'echo "POOL=none (no libvirt pool found on the reference host)"\n'}"""
    }

    /** File-backed disks of a libvirt domain: DISK=<target>|<path> lines. */
    static String vmDisks(String domain) {
        requireDomain(domain)
        HEADER + """virsh domstate '${domain}' >/dev/null 2>&1 || { echo "ERROR=domain ${domain} not defined on this host"; exit 2; }
echo "STATE=\$(virsh domstate '${domain}' 2>/dev/null | head -1)"
virsh domblklist '${domain}' --details 2>/dev/null | awk '\$1=="file" && \$2=="disk" && \$4!="-" {print "DISK="\$3"|"\$4}'
"""
    }

    /** EXISTS=<path> for each given path that exists (used to check disks are present in the snapshot clone). */
    static String existing(List<String> paths) {
        paths.each { requireSafePath(it) }
        HEADER + paths.collect { "[ -e '${it}' ] && echo \"EXISTS=${it}\"" }.join('\n') + '\ntrue\n'
    }

    /**
     * snapconvert SRC TMP: standalone, checked qcow2 copy of a snapshot-time disk image (flattening one level of
     * backing file, which Morpheus VM snapshots leave) at TMP. Needs RMP (the recovery mount); exits on failure.
     */
    private static final String SNAP_CONVERT = '''backing() { qemu-img info -U "$1" 2>/dev/null | sed -n 's/^backing file: \\([^ ]*\\).*/\\1/p'; }
snapconvert() {
  local S="$1" T="$2" B BC C
  rm -f "$T"
  B=$(backing "$S")
  if [ -z "$B" ]; then
    qemu-img convert -O qcow2 "$S" "$T" || { rm -f "$T"; echo "ERROR=convert failed"; exit 4; }
  else
    case "$B" in /*) ;; *) B="$(dirname "$S")/$B";; esac
    BC=$(echo "$B" | sed "s#^/mnt/[^/]*/#$RMP/#")
    [ -e "$BC" ] || { echo "ERROR=backing $B not found in clone"; exit 5; }
    [ -z "$(backing "$BC")" ] || { echo "ERROR=backing chain deeper than one level"; exit 6; }
    qemu-img convert -O qcow2 "json:{\\"driver\\":\\"qcow2\\",\\"file\\":{\\"driver\\":\\"file\\",\\"filename\\":\\"$S\\"},\\"backing\\":{\\"driver\\":\\"qcow2\\",\\"file\\":{\\"driver\\":\\"file\\",\\"filename\\":\\"$BC\\"}}}" "$T" \\
      || { rm -f "$T"; echo "ERROR=convert failed"; exit 4; }
  fi
  qemu-img check -q "$T" >/dev/null 2>&1; C=$?
  [ $C -le 1 ] || { rm -f "$T"; echo "ERROR=restored image failed qemu-img check ($C)"; exit 7; }
}
'''

    /**
     * File-level restore: a standalone copy of a snapshot-time disk as {@code dest} (in the VM's own directory), hot-
     * attached to the RUNNING domain as the first free virtio target, live only - it is not in the domain's persistent
     * definition, so it can never be booted from and is gone from the VM when it powers off. TARGET=, FILE=.
     */
    static String fileRestoreAttach(String source, String recoveryMount, String dest, String domain) {
        [source, recoveryMount, dest].each { requireSafePath(it) }
        requireDomain(domain)
        if (!source.startsWith(recoveryMount + '/') || !(dest ==~ /.*${COPY_SUFFIX}/)) {
            throw new IllegalArgumentException('source must be inside the recovery mount and dest a file-restore copy')
        }
        HEADER + SNAP_CONVERT + """SRC='${source}'; RMP='${recoveryMount}'; DEST='${dest}'; DOM='${domain}'; TMP="\$DEST.restore-tmp"
[ -e "\$SRC" ] || { echo "ERROR=disk not present in the snapshot"; exit 2; }
[ ! -e "\$DEST" ] || { echo "ERROR=\$DEST already exists"; exit 2; }
[ "\$(virsh domstate "\$DOM" 2>/dev/null)" = running ] || { echo "ERROR=domain is not running"; exit 3; }
USED=" \$(virsh domblklist "\$DOM" 2>/dev/null | awk 'NR>2 && \$1!="" {print \$1}' | tr '\\n' ' ') \$(virsh domblklist "\$DOM" --inactive 2>/dev/null | awk 'NR>2 && \$1!="" {print \$1}' | tr '\\n' ' ') "
TGT=""; for l in b c d e f g h i j k l m n o p q r s t u v w x y z; do case "\$USED" in *" vd\$l "*) ;; *) TGT="vd\$l"; break;; esac; done
[ -n "\$TGT" ] || { echo "ERROR=no free disk slot"; exit 3; }
snapconvert "\$SRC" "\$TMP"
mv "\$TMP" "\$DEST" || { rm -f "\$TMP"; echo "ERROR=move failed"; exit 8; }
virsh attach-disk "\$DOM" "\$DEST" "\$TGT" --live --driver qemu --subdriver qcow2 --targetbus virtio --cache none >/dev/null 2>&1 \\
  || { rm -f "\$DEST"; echo "ERROR=attach failed"; exit 9; }
echo "TARGET=\$TGT"
echo "FILE=\$DEST"
"""
    }

    /**
     * Where a file-restore copy's removal time lives: <datastore mount>/.pure-hvm/copies/<copy file name>.expires
     * (epoch ms). Beside the index, so one listing per host finds every copy's timer.
     */
    static final String COPY_TIMER_DIR = '.pure-hvm/copies'

    private static void requireCopyName(String n) {
        if (!(n ==~ /[A-Za-z0-9._:-]{1,128}${COPY_SUFFIX}/)) {
            throw new IllegalArgumentException("Not a file-restore copy name: ${n}")
        }
    }

    /**
     * The file-restore copies in {@code dir} for {@code domain}:
     * COPY=<file>|<bytes>|<target or ->|<expires ms or ->|<mtime s>, target being where it is attached right now (live).
     */
    static String fileRestoreList(String dir, String domain, String prodMount) {
        requireSafePath(dir)
        requireSafePath(prodMount)
        requireDomain(domain)
        HEADER + """D='${dir}'; DOM='${domain}'; TD='${prodMount}/${COPY_TIMER_DIR}'
LIVE=\$(virsh domblklist "\$DOM" 2>/dev/null | awk 'NR>2 && \$1!="" {print \$1"|"\$2}')
for f in "\$D"/"\$DOM".${FILE_RESTORE_TAG}-*.qcow2; do
  [ -e "\$f" ] || continue
  T=\$(echo "\$LIVE" | awk -F'|' -v f="\$f" '\$2==f {print \$1}' | head -1)
  X=-; [ -e "\$TD/\${f##*/}.expires" ] && X=\$(cat "\$TD/\${f##*/}.expires")
  echo "COPY=\$f|\$(stat -c %s "\$f")|\${T:--}|\${X:--}|\$(stat -c %Y "\$f")"
done
true
"""
    }

    /** Sets a file-restore copy's removal time. */
    static String fileRestoreTimer(String prodMount, String copyName, long expiresMs) {
        requireSafePath(prodMount)
        requireCopyName(copyName)
        HEADER + """TD='${prodMount}/${COPY_TIMER_DIR}'
mountpoint -q '${prodMount}' || { echo "ERROR=${prodMount} is not mounted"; exit 2; }
mkdir -p "\$TD" && echo ${expiresMs} > "\$TD/${copyName}.expires.tmp" && mv -f "\$TD/${copyName}.expires.tmp" "\$TD/${copyName}.expires" \\
  && echo "EXPIRES=${expiresMs}" || { echo "ERROR=timer write failed"; exit 3; }
"""
    }

    /**
     * Every file-restore copy on this host's GFS2 datastores (recovery mounts excluded: their copies are snapshot
     * contents): CP=<file>|<mtime s>|<expires ms or ->. Also timers whose copy is gone: STALE=<timer file>.
     */
    static String fileRestoreSweepList() {
        HEADER + """for M in \$(findmnt -rn -t gfs2 -o TARGET); do
  case "\$M" in /mnt/pure-recover-*) continue;; esac
  for f in "\$M"/*/*.${FILE_RESTORE_TAG}-*.qcow2; do
    [ -e "\$f" ] || continue
    X=-; [ -e "\$M/${COPY_TIMER_DIR}/\${f##*/}.expires" ] && X=\$(cat "\$M/${COPY_TIMER_DIR}/\${f##*/}.expires")
    echo "CP=\$f|\$(stat -c %Y "\$f")|\${X:--}"
  done
  for t in "\$M/${COPY_TIMER_DIR}"/*.expires; do
    [ -e "\$t" ] || continue
    n=\${t##*/}; n=\${n%.expires}
    ls "\$M"/*/"\$n" >/dev/null 2>&1 || echo "STALE=\$t"
  done
done
true
"""
    }

    /** Deletes timers of copies that are gone (from fileRestoreSweepList). */
    static String removeStaleTimers(List<String> timers) {
        timers.each { String t ->
            requireSafePath(t)
            if (!(t ==~ /\/mnt\/[^\/]+\/\.pure-hvm\/copies\/[A-Za-z0-9._:-]+\.expires/)) {
                throw new IllegalArgumentException("Not a copy timer: ${t}")
            }
        }
        HEADER + timers.collect { "rm -f '${it}' && echo \"REMOVED=${it}\"" }.join('\n') + '\ntrue\n'
    }

    /** HERE=1 when the domain is defined on this host. */
    static String domainHere(String domain) {
        requireDomain(domain)
        HEADER + "virsh dominfo '${domain}' >/dev/null 2>&1 && echo HERE=1 || echo HERE=0\n"
    }

    // ------------------------------------------------------------------ restore-point index (SnapshotIndex)

    /** Directory, under a datastore's mount, of the restore-point index: one <snapshot>.idx per indexed snapshot. */
    static final String INDEX_DIR = '.pure-hvm/index'

    private static void requireIndexName(String n) {
        if (!(n ==~ /[A-Za-z0-9._-]{1,200}\.idx/)) {
            throw new IllegalArgumentException("Unsafe index name: ${n}")
        }
    }

    private static void requireRelPath(String p) {
        if (!(p ==~ /[A-Za-z0-9._][A-Za-z0-9._\/-]*/) || p.contains('..') || p.contains('//')) {
            throw new IllegalArgumentException("Unsafe relative path: ${p}")
        }
    }

    /** Every file on a recovery mount that could be a VM's disk: IDXFILE=<path under the mount>. Read-only. */
    static String snapshotIndexList(String mountPoint) {
        requireRecoveryMount(mountPoint)
        HEADER + """MP='${mountPoint}'
mountpoint -q "\$MP" || { echo "ERROR=\$MP is not mounted"; exit 2; }
find "\$MP" -xdev \\( -path "\$MP/lost+found" -o -path "\$MP/mvm-hb" -o -path "\$MP/.pure-hvm" \\) -prune -o -type f \\
  -not -name '*.pre-restore-*' -not -name '*.restore-tmp' -not -name '*.xml' -not -name '*.${FILE_RESTORE_TAG}-*' -printf 'IDXFILE=%P\\n'
"""
    }

    /**
     * Writes (atomically) one snapshot's index on the production datastore. The list goes in base64, never as script
     * text: its lines are file names read from the snapshot, and a heredoc could be closed early by one of them.
     */
    static String writeSnapshotIndex(String prodMount, String indexName, List<String> relPaths) {
        requireSafePath(prodMount)
        requireIndexName(indexName)
        relPaths.each { requireRelPath(it) }
        String b64 = (relPaths.join('\n') + '\n').getBytes('UTF-8').encodeBase64().toString()
        HEADER + """D='${prodMount}/${INDEX_DIR}'
mountpoint -q '${prodMount}' || { echo "ERROR=${prodMount} is not mounted"; exit 2; }
mkdir -p "\$D" || { echo "ERROR=mkdir failed"; exit 3; }
echo '${b64}' | base64 -d > "\$D/${indexName}.tmp" && mv -f "\$D/${indexName}.tmp" "\$D/${indexName}" && echo "WROTE=${indexName}"
"""
    }

    /** IDX=<name> for every index on the datastore. */
    static String snapshotIndexNames(String prodMount) {
        requireSafePath(prodMount)
        HEADER + """D='${prodMount}/${INDEX_DIR}'
mountpoint -q '${prodMount}' || { echo "ERROR=${prodMount} is not mounted"; exit 2; }
for f in "\$D"/*.idx; do [ -e "\$f" ] && echo "IDX=\${f##*/}"; done
true
"""
    }

    /** Deletes the given index files (snapshots that no longer exist). */
    static String removeSnapshotIndexes(String prodMount, List<String> indexNames) {
        requireSafePath(prodMount)
        indexNames.each { requireIndexName(it) }
        HEADER + indexNames.collect { "rm -f '${prodMount}/${INDEX_DIR}/${it}' && echo \"REMOVED=${it}\"" }.join('\n') + '\ntrue\n'
    }

    // ------------------------------------------------------------------ Restore tab: one host call per page load

    /**
     * Everything the Restore tab needs from the VM's host in one call:
     *  COPY=<file>|<bytes>|<target or ->      file-restore copies in the VM's directory (see fileRestoreList)
     *  NIC=<mac>|<config link>|<live link>   its network interfaces' link state (live "-" when not running)
     *  IDX=<name>                            every restore-point index on the datastore
     *  HAS=<name>|<path>                     which of the VM's disk paths each index contains
     */
    static String restoreTabInfo(String vmDir, String domain, String prodMount, List<String> diskRelPaths) {
        requireSafePath(vmDir)
        requireSafePath(prodMount)
        requireDomain(domain)
        diskRelPaths.each { requireRelPath(it) }
        fileRestoreList(vmDir, domain, prodMount) + nicBody(domain) + """I='${prodMount}/${INDEX_DIR}'
for f in "\$I"/*.idx; do
  [ -e "\$f" ] || continue
  n=\${f##*/}; echo "IDX=\$n"
""" + diskRelPaths.collect { "  grep -Fxq '${it}' \"\$f\" && echo \"HAS=\$n|${it}\"" }.join('\n') + """
done
true
"""
    }

    private static String nicBody(String domain) {
        """DOMN='${domain}'
RUN=0; [ "\$(virsh domstate "\$DOMN" 2>/dev/null)" = running ] && RUN=1
MACS=\$(virsh domiflist "\$DOMN" --inactive 2>/dev/null | awk 'NR>2 && \$5!="" {print \$5}')
for m in \$MACS; do
  C=\$(virsh domif-getlink "\$DOMN" "\$m" --config 2>/dev/null | awk '{print \$2}')
  L=-; [ \$RUN = 1 ] && L=\$(virsh domif-getlink "\$DOMN" "\$m" 2>/dev/null | awk '{print \$2}')
  echo "NIC=\$m|\${C:-?}|\${L:-?}"
done
"""
    }

    /**
     * Sets every network interface's link of a domain up or down: in its persistent definition, and live when it is
     * running. A restored clone starts with its links down so its copy of the original's identity stays off the network.
     */
    static String setLinks(String domain, boolean up) {
        requireDomain(domain)
        String st = up ? 'up' : 'down'
        HEADER + nicBody(domain) + """for m in \$MACS; do
  virsh domif-setlink "\$DOMN" "\$m" ${st} --config >/dev/null 2>&1 || { echo "ERROR=setting \$m ${st} failed"; exit 3; }
  if [ \$RUN = 1 ]; then virsh domif-setlink "\$DOMN" "\$m" ${st} >/dev/null 2>&1 || { echo "ERROR=setting \$m ${st} (live) failed"; exit 3; }; fi
  echo "SET=\$m"
done
echo "LINK=${st}"
"""
    }

    /** How fileRestoreRemove decides; see there. */
    static enum RemoveMode { USER, REAP_EXPIRED, REAP_IF_DETACHED, ORPHAN }

    /**
     * Detaches a file-restore copy from the running domain (if attached) and deletes it and its timer.
     *  USER              - the customer's Remove: always
     *  REAP_EXPIRED      - the sweeper, timer run out: only on the host where the domain is defined (a live-migrated
     *                      VM has the copy attached elsewhere; that host's turn comes when Morpheus knows it), SKIP=
     *  REAP_IF_DETACHED  - the sweeper, timer still running: only if not attached any more (the VM powered off), KEEP=
     *  ORPHAN            - the VM no longer exists in Morpheus: only if the domain is not defined on this host
     */
    static String fileRestoreRemove(String file, String domain, String prodMount, RemoveMode mode = RemoveMode.USER) {
        requireSafePath(file)
        requireSafePath(prodMount)
        requireDomain(domain)
        if (!(file ==~ /.*\/${java.util.regex.Pattern.quote(domain)}${COPY_SUFFIX}/) || !file.startsWith(prodMount + '/')) {
            throw new IllegalArgumentException('not a file-restore copy of this VM')
        }
        String guard = [
                (RemoveMode.USER)            : '',
                (RemoveMode.REAP_EXPIRED)    : 'virsh dominfo "$DOM" >/dev/null 2>&1 || { echo "SKIP=domain not on this host"; exit 0; }\n',
                (RemoveMode.REAP_IF_DETACHED): 'virsh dominfo "$DOM" >/dev/null 2>&1 || { echo "SKIP=domain not on this host"; exit 0; }\n' +
                        '[ -n "$T" ] && { echo "KEEP=attached as $T"; exit 0; }\n',
                (RemoveMode.ORPHAN)          : 'virsh dominfo "$DOM" >/dev/null 2>&1 && { echo "SKIP=domain still defined here"; exit 0; }\n',
        ][mode]
        HEADER + """F='${file}'; DOM='${domain}'; TM='${prodMount}/${COPY_TIMER_DIR}/'"\${F##*/}.expires"
[ -e "\$F" ] || { rm -f "\$TM"; echo "ERROR=copy not found"; exit 2; }
at() { virsh domblklist "\$DOM" 2>/dev/null | awk -v f="\$F" 'NR>2 && \$2==f {print \$1}'; }
T=\$(at); T=\${T%%\$'\\n'*}
""" + guard + """if [ -n "\$T" ]; then
  virsh detach-disk "\$DOM" "\$T" --live >/dev/null 2>&1 || { echo "ERROR=detach refused"; exit 3; }
  for i in \$(seq 1 30); do [ -z "\$(at)" ] && break; sleep 1; done
  [ -z "\$(at)" ] || { echo "ERROR=the guest did not release the disk; unmount it in the guest and retry"; exit 4; }
  echo "DETACHED=\$T"
fi
rm -f "\$F" && echo "REMOVED=\$F"
rm -f "\$TM"
"""
    }

    /**
     * Restores ONE disk of a powered-off VM in place from the recovery mount: writes a standalone copy of the
     * snapshot-time image next to the live file, then swaps it in and keeps the live file as <dest>.pre-restore-<ts>
     * (undo = move it back). Refuses when the live disk has a backing chain (the VM has Morpheus snapshots).
     */
    static String restoreVmDisk(String source, String recoveryMount, String prodMount, String dest, String ts) {
        [source, recoveryMount, prodMount, dest].each { requireSafePath(it) }
        if (!(ts ==~ /[0-9]{8,14}/)) {
            throw new IllegalArgumentException('bad timestamp')
        }
        if (!source.startsWith(recoveryMount + '/') || !dest.startsWith(prodMount + '/')) {
            throw new IllegalArgumentException('source must be inside the recovery mount and dest inside the production mount')
        }
        HEADER + SNAP_CONVERT + """SRC='${source}'; RMP='${recoveryMount}'; PMP='${prodMount}'; DEST='${dest}'; KEEP="\$DEST.pre-restore-${ts}"; TMP="\$DEST.restore-tmp"
[ -e "\$DEST" ] || { echo "ERROR=live disk \$DEST not found"; exit 2; }
[ -e "\$SRC" ] || { echo "ERROR=disk not present in the snapshot"; exit 2; }
[ -z "\$(backing "\$DEST")" ] || { echo "ERROR=VM has snapshots (live disk has a backing chain); delete them or restore as files"; exit 3; }
OWN=\$(stat -c %u:%g "\$DEST"); MODE=\$(stat -c %a "\$DEST")
snapconvert "\$SRC" "\$TMP"
mv "\$DEST" "\$KEEP" && mv "\$TMP" "\$DEST" || { [ -e "\$KEEP" ] && [ ! -e "\$DEST" ] && mv "\$KEEP" "\$DEST"; echo "ERROR=swap failed"; exit 8; }
chown "\$OWN" "\$DEST"; chmod "\$MODE" "\$DEST"
echo "KEPT=\$KEEP"
echo "SIZE=\$(stat -c %s "\$DEST")"
"""
    }

    /**
     * After "clone to new VM": the kept file is the new VM's blank image that the snapshot copy replaced, not customer
     * data, so it is removed instead of left as a .pre-restore copy.
     */
    static String removeKept(String dest, String kept) {
        requireKept(dest, kept)
        HEADER + """[ -e '${dest}' ] || { echo "ERROR=restored disk missing; kept file left in place"; exit 2; }
rm -f '${kept}' && echo "REMOVED=${kept}"
"""
    }

    /**
     * Like removeKept, but only when the kept file never held data (a disk Morpheus just added: every qcow2 extent
     * unallocated). Anything else stays as a .pre-restore copy: KEPT_DATA=<kept>.
     */
    static String removeKeptIfBlank(String dest, String kept) {
        requireKept(dest, kept)
        HEADER + """[ -e '${dest}' ] || { echo "ERROR=restored disk missing; kept file left in place"; exit 2; }
M=\$(qemu-img map -U --output=json '${kept}' 2>/dev/null) || { echo "KEPT_DATA=${kept}"; exit 0; }
# no "echo | grep -q": under pipefail grep's early exit SIGPIPEs echo and reads as "no data" (seen 2026-10-21)
case "\$M" in
  '') echo "KEPT_DATA=${kept}" ;;
  *'"data": true'*|*'"data":true'*) echo "KEPT_DATA=${kept}" ;;
  *) rm -f '${kept}' && echo "REMOVED=${kept}" ;;
esac
"""
    }

    /** Grows a (powered-off) qcow2 to the size Morpheus records for it, when the restored image is smaller. Never shrinks. */
    static String growTo(String path, long bytes) {
        requireSafePath(path)
        HEADER + """V=\$(vsize '${path}')
[ -n "\$V" ] || { echo "ERROR=cannot read virtual size"; exit 2; }
if [ "\$V" -lt ${bytes} ]; then qemu-img resize -q -f qcow2 '${path}' ${bytes} || { echo "ERROR=resize failed"; exit 3; }; echo "GROWN=\$V"; fi
echo "VSIZE=\$(vsize '${path}')"
"""
    }

    /** Undo for restoreVmDisk: put the kept pre-restore file back. */
    static String undoRestoreVmDisk(String dest, String kept) {
        requireKept(dest, kept)
        HEADER + """[ -e '${kept}' ] || { echo "ERROR=kept file missing"; exit 2; }
mv -f '${kept}' '${dest}' && echo "UNDONE=${dest}"
"""
    }

    /**
     * Give a snapshot clone its own identity so it can be mounted cluster-wide next to production (workflow ii):
     * new lock table (same cluster id, new fs name), new UUID, journals replayed once locally, stale Morpheus
     * heartbeat directory moved aside. Verifies the lock table actually changed (RH 4075151).
     */
    static String resignature(String wwid, String newFsName) {
        requireWwid(wwid)
        if (!(newFsName ==~ /[a-z0-9_-]{1,30}/)) {
            throw new IllegalArgumentException("bad fs name ${newFsName}")
        }
        rescan(wwid) + """D=/dev/mapper/\$W
[ -e "\$D" ] || { echo "ERROR=device did not appear"; exit 2; }
findmnt -rn -S "\$D" >/dev/null && { echo "ERROR=clone is mounted"; exit 3; }
OLD=\$(lt "\$D"); CL=\${OLD%%:*}
[ -n "\$CL" ] || { echo "ERROR=no lock table on device"; exit 4; }
NEW="\$CL:${newFsName}"
[ "\$OLD" != "\$NEW" ] || { echo "ERROR=new lock table equals the old one"; exit 5; }
tunegfs2 -o locktable="\$NEW" "\$D" >/dev/null 2>&1 || true
[ "\$(lt "\$D")" = "\$NEW" ] || tunegfs2 -L "\$NEW" "\$D" >/dev/null 2>&1 || true
[ "\$(lt "\$D")" = "\$NEW" ] || { echo "ERROR=lock table did not change (\$(lt "\$D"))"; exit 6; }
tunegfs2 -U "\$(cat /proc/sys/kernel/random/uuid)" "\$D" >/dev/null || { echo "ERROR=uuid change failed"; exit 7; }
MP=\$(mktemp -d /mnt/pure-resign-XXXXXX)
mount -t gfs2 -o lockproto=lock_nolock "\$D" "\$MP" || { rmdir "\$MP"; echo "ERROR=replay mount failed"; exit 8; }
[ -d "\$MP/mvm-hb" ] && mv "\$MP/mvm-hb" "\$MP/mvm-hb.from-snapshot-\$(date +%s)"
umount "\$MP"; rmdir "\$MP"
echo "OLD_LOCKTABLE=\$OLD"
echo "LOCKTABLE=\$NEW"
"""
    }

    /**
     * Remove a LUN from one host (workflow iii): unmount if ours, refuse if busy/open, flush the map,
     * delete the SCSI paths, forget the WWID. Must run on every host BEFORE the array disconnect.
     */
    static String hostCleanup(String wwid, String mountPoint = null) {
        requireWwid(wwid)
        if (mountPoint) {
            requireSafePath(mountPoint)
        }
        HEADER + """W=${wwid}; MP='${mountPoint ?: ''}'
if [ -n "\$MP" ] && mountpoint -q "\$MP"; then
  fuser -m "\$MP" >/dev/null 2>&1 && { echo "ERROR=\$MP is busy"; exit 3; }
  umount "\$MP" || { echo "ERROR=umount failed"; exit 3; }
  rmdir "\$MP" 2>/dev/null || true
fi
findmnt -rn -S /dev/mapper/\$W >/dev/null && { echo "ERROR=still mounted at \$(findmnt -rn -S /dev/mapper/\$W -o TARGET)"; exit 4; }
P=\$(paths_of \$W)
if [ -e /dev/mapper/\$W ]; then
  O=\$(dmsetup info -c --noheadings -o open \$W 2>/dev/null | tr -d ' ')
  [ "\${O:-0}" = "0" ] || { echo "ERROR=map open count \$O"; exit 5; }
  multipath -f \$W >/dev/null || { echo "ERROR=multipath -f failed"; exit 6; }
fi
for d in \$P; do blockdev --flushbufs /dev/\$d 2>/dev/null || true; echo 1 > /sys/block/\$d/device/delete; done
multipath -w \$W >/dev/null 2>&1 || true
echo "CLEAN=1"
"""
    }

    static final String GFS2_MOUNT_PATH = /\/mnt\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/

    /**
     * Shell lines that remove the libvirt pool(s) targeting a GFS2 datastore mount path, only when that path is not a
     * mount point and is empty (a datastore Morpheus has deleted). With a pool name, only that pool; else every pool
     * whose target is the path.
     */
    static String removePoolBody(String mountPath, String poolName = null) {
        requireSafePath(mountPath)
        if (!(mountPath ==~ GFS2_MOUNT_PATH)) {
            throw new IllegalArgumentException("Not a GFS2 datastore mount path: ${mountPath}")
        }
        requirePoolName(poolName)
        String pools = poolName ? "'${poolName}'" :
                "\$(for p in \$(virsh pool-list --all --name 2>/dev/null); do [ \"\$(virsh pool-dumpxml \"\$p\" 2>/dev/null | sed -n 's:.*<path>\\(.*\\)</path>.*:\\1:p' | head -1)\" = \"\$PMP\" ] && echo \"\$p\"; done)"
        """PMP='${mountPath}'
if mountpoint -q "\$PMP"; then echo "SKIP \$PMP is still mounted; pool left alone"
elif [ -d "\$PMP" ] && [ -n "\$(ls -A "\$PMP" 2>/dev/null)" ]; then echo "SKIP \$PMP is not empty; pool left alone"
else
  for PN in ${pools}; do
    virsh pool-destroy "\$PN" >/dev/null 2>&1 || true
    virsh pool-undefine "\$PN" >/dev/null 2>&1 && echo "POOL_REMOVED=\$PN (\$PMP)" || echo "WARN=could not undefine pool \$PN"
  done
  rmdir "\$PMP" 2>/dev/null || true
fi
"""
    }

    /** Removes the libvirt pool of a deleted GFS2 datastore on one host (see removePoolBody). */
    static String removePools(String mountPath) {
        HEADER + removePoolBody(mountPath)
    }

    /** Re-lists the disk files of an existing recovery mount (same filter as recoveryMount); read-only. */
    static String recoveryFiles(String mountPoint) {
        requireRecoveryMount(mountPoint)
        HEADER + """MP='${mountPoint}'
mountpoint -q "\$MP" || { echo "ERROR=\$MP is not mounted"; exit 2; }
""" + RECOVERY_FILES
    }

    // ------------------------------------------------------------------ reclaim (restore leftovers)

    /** Leftovers of this plugin's restores: <disk>.pre-restore-<UTC> (the replaced disk) and file-restore copies. */
    static final String LEFTOVER = /(\.pre-restore-\d{8,14}|${COPY_SUFFIX})$/

    /** INUSE=<file> for every file any libvirt domain on this host uses as a disk (running and persistent definitions). */
    private static final String IN_USE = '''for d in $(virsh list --all --name 2>/dev/null); do
  { virsh domblklist "$d" --details 2>/dev/null; virsh domblklist "$d" --details --inactive 2>/dev/null; } | awk '$1=="file" && $4!="-" {print "INUSE="$4}'
done
'''

    /**
     * Read-only, for each datastore mount: MOUNTED= or NOTMOUNTED=, and its restore-leftover candidates as
     * LEFT=<mount>|<path under it>|<bytes>|<mtime s>; then INUSE= lines (see IN_USE). The caller filters by LEFTOVER.
     */
    static String leftoverScan(List<String> mounts) {
        mounts.each { requireSafePath(it) }
        HEADER + """for MP in ${mounts.collect { "'${it}'" }.join(' ')}; do
  mountpoint -q "\$MP" || { echo "NOTMOUNTED=\$MP"; continue; }
  echo "MOUNTED=\$MP"
  find "\$MP" -xdev \\( -path "\$MP/lost+found" -o -path "\$MP/mvm-hb" -o -path "\$MP/.pure-hvm" \\) -prune -o -type f \\
    \\( -name '*.pre-restore-*' -o -name '*.${FILE_RESTORE_TAG}-*.qcow2' \\) -printf "LEFT=\$MP|%P|%s|%T@\\n"
done
""" + IN_USE + 'true\n'
    }

    /**
     * Deletes restore leftovers from a GFS2 datastore: only regular files (not links) whose names are leftovers and that
     * no domain on this host uses (the caller has checked the other hosts). REMOVED=, INUSE=, GONE= or FAILED= per path.
     * A file-restore copy's timer goes with it.
     */
    static String removeLeftovers(String mountPoint, List<String> relPaths) {
        requireSafePath(mountPoint)
        if (!(mountPoint ==~ GFS2_MOUNT_PATH)) {
            throw new IllegalArgumentException("Not a GFS2 datastore mount path: ${mountPoint}")
        }
        relPaths.each { String p ->
            requireRelPath(p)
            if (!(p ==~ /.*${LEFTOVER}/)) {
                throw new IllegalArgumentException("Not a restore leftover: ${p}")
            }
        }
        HEADER + """MP='${mountPoint}'
mountpoint -q "\$MP" || { echo "ERROR=\$MP is not mounted on this host"; exit 2; }
USED=\$(${IN_USE.trim()})
NL='
'
gone() {
  F="\$MP/\$1"
  if [ -L "\$F" ] || [ ! -f "\$F" ]; then echo "GONE=\$1"; return; fi
  # case, not "echo | grep -q": under pipefail grep's early exit fails the pipeline
  case "\$NL\$USED\$NL" in *"\${NL}INUSE=\$F\${NL}"*) echo "INUSE=\$1"; return;; esac
  rm -f -- "\$F" && echo "REMOVED=\$1" || { echo "FAILED=\$1"; return; }
  rm -f "\$MP/${COPY_TIMER_DIR}/\${F##*/}.expires"
}
""" + relPaths.collect { "gone '${it}'" }.join('\n') + '\ntrue\n'
    }

    static final int LIST_FILES_MAX = 500

    /**
     * Read-only inventory of a datastore mount for Retire: every regular file as FILE=<relative path>|<bytes>
     * (lost+found skipped, capped), plus COUNT. MOUNTED=0 when nothing is mounted there.
     */
    static String listFiles(String mountPoint) {
        requireSafePath(mountPoint)
        HEADER + """MP='${mountPoint}'
mountpoint -q "\$MP" || { echo "MOUNTED=0"; exit 0; }
echo "MOUNTED=1"
echo "COUNT=\$(find "\$MP" -xdev -path "\$MP/lost+found" -prune -o -type f -print 2>/dev/null | wc -l)"
find "\$MP" -xdev -path "\$MP/lost+found" -prune -o -type f -printf 'FILE=%P|%s\\n' 2>/dev/null | head -${LIST_FILES_MAX}
# head closing early SIGPIPEs find once the listing is long; without this the script "fails" and Retire would read
# a full datastore as unreadable
true
"""
    }

    /** Mount point and filesystem size/free of the GFS2 on one LUN, as this host sees it. */
    static String fsSize(String wwid) {
        requireWwid(wwid)
        HEADER + """MP=\$(mnt_of ${wwid})
[ -n "\$MP" ] || { echo "ERROR=not mounted on this host"; exit 2; }
echo "MOUNT=\$MP"
echo "FSSIZE=\$(df -B1 --output=size "\$MP" | tail -1 | tr -d ' ')"
echo "FSFREE=\$(df -B1 --output=avail "\$MP" | tail -1 | tr -d ' ')"
"""
    }

    static String findMount(String wwid) {
        requireWwid(wwid)
        HEADER + "echo \"MOUNT=\$(mnt_of ${wwid})\"\n"
    }
}
