#!/bin/bash
# Pure FlashArray / HVM host probe. READ-ONLY: changes nothing on the host.
# Usage: pure-host-probe.sh <portal-ip> [<portal-ip> ...]
# Prints "@@<section>" headers followed by raw command output; the plugin parses it.
# CHAP secrets are never printed (node records are filtered to an allow-list of keys).
export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
PORTALS="$*"

sec() { echo "@@$1"; }

sec os;       . /etc/os-release 2>/dev/null; echo "VERSION_ID=${VERSION_ID}"; echo "KERNEL=$(uname -r)"
sec pkgs;     dpkg-query -W -f='${Package}|${Version}|${db:Status-Abbrev}\n' open-iscsi multipath-tools sg3-utils gfs2-utils 2>/dev/null
sec services; for s in iscsid multipathd; do echo "$s|$(systemctl is-active $s 2>/dev/null)|$(systemctl is-enabled $s 2>/dev/null)"; done
sec iqn;      sed -n 's/^InitiatorName=//p' /etc/iscsi/initiatorname.iscsi 2>/dev/null
sec ifaces;   iscsiadm -m iface 2>/dev/null
sec sessions; iscsiadm -m session -P1 2>/dev/null | grep -E '^Target:|Current Portal:|Iface Name:|iSCSI Session State:|Iface Netdev:'
sec nodes;    iscsiadm -m node -o show 2>/dev/null | grep -E '^(# BEGIN RECORD|# END RECORD|node\.name|node\.conn\[0\]\.address|node\.startup|iface\.iscsi_ifacename|iface\.net_ifacename|node\.conn\[0\]\.timeo\.noop_out_interval|node\.conn\[0\]\.timeo\.noop_out_timeout)( |$)'
sec mpconf
multipath -t 2>/dev/null | awk '
  /^[[:space:]]*device \{/ {blk=$0 "\n"; dev=1; pure=0; next}
  dev { blk=blk $0 "\n"; if ($0 ~ /vendor "PURE"/) pure=1; if ($0 ~ /^[[:space:]]*\}/) { if (pure) printf "%s", blk; dev=0 } }'
# section|key|value. polling_interval only exists in defaults, and no_path_retry may be set above or below the device entry
sec mpglobal
multipath -t 2>/dev/null | awk '
  /^defaults \{/ {s="defaults"; next}
  /^overrides \{/ {s="overrides"; next}
  /^[^[:space:]]/ {s=""; next}
  s && ($1 == "no_path_retry" || (s == "defaults" && $1 == "polling_interval")) {gsub(/"/, "", $2); print s "|" $1 "|" $2}'
# wwid|no_path_retry of each multipaths{} entry that sets it: such an entry beats overrides, device and defaults for that map
sec mpentries
multipath -t 2>/dev/null | awk '
  /^multipaths \{/ {m=1; next}
  m && /^\}/ {m=0}
  m && $1 == "multipath" {w=""; r=""}
  m && $1 == "wwid" {gsub(/"/, "", $2); w=$2}
  m && $1 == "no_path_retry" {gsub(/"/, "", $2); r=$2}
  m && /^[[:space:]]+\}/ {if (w != "" && r != "") print w "|" r; w=""; r=""}'
sec maps;     multipathd show maps raw format "%w|%n|%Q|%N" 2>/dev/null
sec paths;    multipathd show paths raw format "%w|%d|%t|%T|%p|%s" 2>/dev/null
sec sysfs
for d in /sys/block/sd*; do
  [ -e "$d/device/vendor" ] || continue
  v=$(tr -d ' ' < "$d/device/vendor")
  [ "$v" = "PURE" ] || continue
  sch=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "$d/queue/scheduler")
  echo "$(basename $d)|$sch|$(cat $d/queue/add_random)|$(cat $d/queue/rq_affinity)|$(cat $d/queue/max_sectors_kb)|$(cat $d/device/timeout)"
done
for d in /sys/block/dm-*; do
  n=$(cat "$d/dm/name" 2>/dev/null); case "$n" in 3624a937*) ;; *) continue;; esac
  sch=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "$d/queue/scheduler")
  echo "$n|$sch|$(cat $d/queue/add_random)|$(cat $d/queue/rq_affinity)|$(cat $d/queue/max_sectors_kb)|-"
done
# portal|outgoing dev|gateway (empty = directly connected; a gateway means it is not a storage path for this host)
sec routes;   for p in $PORTALS; do r=$(ip -o route get "$p" 2>/dev/null); echo "$p|$(echo "$r" | sed -n 's/.* dev \([^ ]*\).*/\1/p')|$(echo "$r" | sed -n 's/.* via \([^ ]*\).*/\1/p')"; done
sec links;    ip -o link show 2>/dev/null | sed -E 's/^[0-9]+: ([^:@]+)[^ ]* .* mtu ([0-9]+).*/\1|\2/'
sec addrs;    ip -o -4 addr show 2>/dev/null | awk '{print $2"|"$4}'
sec ping;     for p in $PORTALS; do if ping -c2 -W1 -M do -s 8972 "$p" >/dev/null 2>&1; then echo "$p|ok"; else echo "$p|fail"; fi; done
sec sysctl
for i in all default $(for p in $PORTALS; do ip -o route get "$p" 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p'; done | sort -u); do
  for k in arp_ignore arp_announce rp_filter; do echo "$i|$k|$(cat /proc/sys/net/ipv4/conf/$i/$k 2>/dev/null)"; done
done
# storage NIC|netplan file defining it (empty if none): who owns the host network (hvmcli virtSwitch, Morpheus base)
sec netplan
for i in $(for p in $PORTALS; do ip -o route get "$p" 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p'; done | sort -u); do
  echo "$i|$(grep -lE "^[[:space:]]+${i}:" /etc/netplan/*.yaml 2>/dev/null | head -1)"
done
sec gfs2mounts; findmnt -t gfs2 -rn -o SOURCE,TARGET,OPTIONS 2>/dev/null
sec gfs2withdraw; for f in /sys/fs/gfs2/*/withdraw; do [ -e "$f" ] && echo "$(basename $(dirname $f))|$(cat $f)"; done
sec gfs2tables
# blkid reads the device: on a map with no usable path it would block until the paths return (for ever with queue)
USABLE=$(multipathd show paths raw format "%w|%T" 2>/dev/null | awk -F'|' '$2 ~ /ready|ghost/ {print $1}' | sort -u)
for dev in /dev/mapper/3624a937*; do
  [ -e "$dev" ] || continue
  echo "$USABLE" | grep -qx "$(basename "$dev")" || continue
  if timeout 15 blkid -p -o value -s TYPE "$dev" 2>/dev/null | grep -qx gfs2; then
    echo "$dev|$(timeout 15 tunegfs2 -l "$dev" 2>/dev/null | sed -n 's/^[Ll]ock [Tt]able[^:]*: *//p' | head -1)"
  fi
done
# libvirt storage pools: name|target path|state
sec pools;    for p in $(virsh pool-list --all --name 2>/dev/null); do echo "$p|$(virsh pool-dumpxml "$p" 2>/dev/null | sed -n 's:.*<path>\(.*\)</path>.*:\1:p' | head -1)|$(virsh pool-info "$p" 2>/dev/null | awk -F': *' '/^State/ {print $2}')"; done
sec hvmcli;   command -v hvmcli 2>/dev/null
# interface|operstate: an iSCSI iface bound to a missing or down NIC makes an unbound discovery time out
sec linkstate; for n in /sys/class/net/*; do echo "$(basename "$n")|$(cat "$n/operstate" 2>/dev/null)"; done
# hvmcli virtSwitches: name|base_type|configured mtu|traffic interfaces (comma list)
sec virtswitch
for f in /etc/hvm/virtswitch/*.yaml; do
  [ -e "$f" ] || continue
  awk '/^name:/ {n=$2} /^base_type:/ {t=$2} /^mtu:/ {m=$2} /^[[:space:]]+interface:/ {i=i (i?",":"") $2} END {print n "|" t "|" m "|" i}' "$f"
done
sec end
