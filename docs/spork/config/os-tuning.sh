#!/usr/bin/env bash
# SPORK day-1 OS tuning for a 126 GB, 32-core, NVMe read-heavy node (n1 to n4).
# Source: docs/spork/02-retrieval-speed.md 5.3 (C10), 3.1; docs/spork/01-architecture.md 0.6 (LVM on LUKS), 1.3, 1.4.
#
# THIS IS DOCUMENTATION IN COMMAND FORM. It is not meant to be executed blind: every block names the doc item it
# implements; adapt device names, cgroup paths and the mount point, then run block by block as root. Nothing here is
# run by the ArcadeDB server.
set -euo pipefail

: "${SPORK_NVME:=/dev/nvme0n1}"            # the data drive (one 3.84 TB NVMe per node at low/mid, 0.6)
: "${SPORK_LUKS_NAME:=spork0}"             # dm-crypt mapping name
: "${SPORK_VG:=spork}"                     # LVM volume group (one VG, one linear LV, one XFS filesystem, 1.3)
: "${SPORK_LV:=data}"
: "${SPORK_MOUNT:=/var/lib/spork}"         # databases root, Raft storage, ROC segments, staging: ONE filesystem (1.3)

# ---------------------------------------------------------------------------------------------------------------
# 1. Transparent huge pages (5.3; required by -XX:+UseTransparentHugePages in jvm.options)
#    madvise: only the JVM's madvise()d heap gets 2 MB pages; the OS page cache, ROC and the Ratis log keep 4 KB pages
#    (huge pages in the file cache would make a 32 KB random read pull 2 MB). defrag=defer+madvise so the JVM's
#    pre-touch can compact synchronously while everything else defers.
# ---------------------------------------------------------------------------------------------------------------
echo madvise       > /sys/kernel/mm/transparent_hugepage/enabled
echo defer+madvise > /sys/kernel/mm/transparent_hugepage/defrag
# Persist: add "transparent_hugepage=madvise" to the kernel command line, or a tmpfiles.d/systemd unit that rewrites
# the two sysfs files at boot.

# ---------------------------------------------------------------------------------------------------------------
# 2. Block device (5.3, 3.1)
#    scheduler none: NVMe has its own queues. read_ahead_kb=32 (blockdev --setra takes 512 B sectors: 64 = 32 KB):
#    the engine reads whole 32 KB Record pages at random and BucketIterator already batches scans, so the stock 128 KB
#    read-ahead would move 4x the bytes per miss. nr_requests stays at the NVMe default (1023).
# ---------------------------------------------------------------------------------------------------------------
NVME_BASENAME="$(basename "$SPORK_NVME")"
echo none > "/sys/block/${NVME_BASENAME}/queue/scheduler"
echo 32   > "/sys/block/${NVME_BASENAME}/queue/read_ahead_kb"
# cat /sys/block/${NVME_BASENAME}/queue/nr_requests        # expect 1023; do not lower
# The read-ahead must ALSO be set on the stacked devices the filesystem actually sits on (dm-crypt and the LV inherit
# their own queue settings):
blockdev --setra 64 "/dev/mapper/${SPORK_LUKS_NAME}"
blockdev --setra 64 "/dev/${SPORK_VG}/${SPORK_LV}"
# Persist with a udev rule, e.g. /etc/udev/rules.d/60-spork-nvme.rules:
#   ACTION=="add|change", KERNEL=="nvme[0-9]n[0-9]", ATTR{queue/scheduler}="none", ATTR{queue/read_ahead_kb}="32"
#   ACTION=="add|change", KERNEL=="dm-*", ATTR{queue/read_ahead_kb}="32"

# ---------------------------------------------------------------------------------------------------------------
# 3. LUKS and LVM (0.6: "LVM on LUKS", each drive its own LUKS container and PV; 5.3: dm-crypt without read/write
#    workqueues so reads complete inline instead of being queued to a kernel worker, 10 to 30 us per read)
#    Initial layout (first drive). The key is KMS-held and unlocked at boot from the initramfs (clevis/tang or a
#    TPM-sealed keyfile, 0.6); the keyfile path below is a placeholder for that mechanism.
# ---------------------------------------------------------------------------------------------------------------
# cryptsetup luksFormat --type luks2 "$SPORK_NVME" /path/to/kms-held.key
# cryptsetup open --perf-no_read_workqueue --perf-no_write_workqueue \
#     --key-file /path/to/kms-held.key "$SPORK_NVME" "$SPORK_LUKS_NAME"
# Make the two --perf flags persistent in the LUKS2 header so the initramfs unlock keeps them:
# cryptsetup --perf-no_read_workqueue --perf-no_write_workqueue --persistent refresh "$SPORK_LUKS_NAME"
# pvcreate "/dev/mapper/${SPORK_LUKS_NAME}"
# vgcreate "$SPORK_VG" "/dev/mapper/${SPORK_LUKS_NAME}"
# lvcreate -l 100%FREE -n "$SPORK_LV" "$SPORK_VG"
#
# XFS (5.3): noatime (no inode write per read), inode64, logbsize=256k.
# mkfs.xfs -L spork "/dev/${SPORK_VG}/${SPORK_LV}"
# mkdir -p "$SPORK_MOUNT"
# mount -o noatime,inode64,logbsize=256k "/dev/${SPORK_VG}/${SPORK_LV}" "$SPORK_MOUNT"
# /etc/fstab:  LABEL=spork  /var/lib/spork  xfs  noatime,inode64,logbsize=256k  0 0
#
# Online expansion when the second drive arrives (0.6; nothing moves between mount points, a linear LV across two
# drives makes either drive's failure a full-node loss with a 4 to 6.5 h rebuild, accepted in R9):
# cryptsetup luksFormat --type luks2 /dev/nvme1n1 /path/to/kms-held.key
# cryptsetup open --perf-no_read_workqueue --perf-no_write_workqueue --key-file /path/to/kms-held.key /dev/nvme1n1 spork1
# pvcreate /dev/mapper/spork1
# vgextend "$SPORK_VG" /dev/mapper/spork1
# lvextend -l +100%FREE "/dev/${SPORK_VG}/${SPORK_LV}"
# xfs_growfs "$SPORK_MOUNT"
# Rollout one node at a time with arcadedb.ha.logPurgeUptoSnapshot=false already in place (0.6) and scheduled full
# compactions suspended for the window.

# ---------------------------------------------------------------------------------------------------------------
# 4. VM sysctls (5.3)
#    swappiness=1: never swap the heap or ROC templates. dirty_background_ratio=5 / dirty_ratio=20: followers write
#    applied page deltas and the Ratis log. max_map_count: jvector maps its .vecgraph files. file-max: one FileChannel
#    per component file, about 12 databases x hundreds of files.
# ---------------------------------------------------------------------------------------------------------------
cat > /etc/sysctl.d/90-spork.conf <<'EOF'
vm.swappiness = 1
vm.dirty_background_ratio = 5
vm.dirty_ratio = 20
vm.max_map_count = 1048576
fs.file-max = 1048576
EOF
sysctl --system
# Service unit: LimitNOFILE=1048576 in the arcadedb systemd unit ([Service] section), matching fs.file-max.

# ---------------------------------------------------------------------------------------------------------------
# 5. cgroups v2 (5.3, 1.4): JVM, roc-search, roc-work, encoder and enrichment workers in separate cgroups with
#    memory.max at their budgets so a ROC gallery growth cannot evict the OS file cache the JVM relies on.
#    memory.high on the JVM cgroup 2 GB below max so pressure shows as a metric before an OOM kill.
#    n1 to n3: JVM 56 heap + 3 native + 4 direct = 63 GB (high 61); ROC 45 GB; encoder sidecar 3 GB (1.4).
#    n4:       JVM 43 + 3 + 4 = 50 GB (high 48); ROC 45 GB; encoder 3 GB; build slice JVM 16 GB (1.4).
#    CPU: roc-search 16 cores by cpuset; roc-work 2 cores on n1 to n3, 6 on n4; encoder 4 cores (2 on n1); the JVM
#    gets the other socket plus whatever ROC leaves idle through cpu.weight, NOT a hard quota (5.3).
#    NUMA: no numactl --membind for the JVM (56 GB needs memory from both 64 GB sockets); -XX:+UseNUMA handles locality.
# ---------------------------------------------------------------------------------------------------------------
CG=/sys/fs/cgroup
for g in spork-jvm roc-search roc-work encode enrich; do mkdir -p "$CG/$g"; done
echo "+memory +cpu +cpuset" > "$CG/cgroup.subtree_control"
# n1 to n3 values (n4: 50G / 48G for spork-jvm; add spork-build with memory.max=16G)
echo 63G > "$CG/spork-jvm/memory.max"
echo 61G > "$CG/spork-jvm/memory.high"
echo 45G > "$CG/roc-search/memory.max"       # roc-serve processes and the in-RAM gallery quarter (0.7)
echo 16  > "$CG/roc-search/cpuset.cpus.partition" 2>/dev/null || true   # placeholder: pin 16 cores of one socket, e.g. "16-31"
echo 3G  > "$CG/encode/memory.max"
echo 2G  > "$CG/enrich/memory.max"           # stateless enrichment workers (1.4 build-slice row)
echo 1000 > "$CG/spork-jvm/cpu.weight"       # cpu.weight, not cpu.max: an idle ROC core serves database reads
# systemd equivalent: Slice= and MemoryMax=/MemoryHigh=/AllowedCPUs=/CPUWeight= in the unit files for arcadedb,
# roc-agent (roc-search, roc-work), the encoder sidecar and the enrichment workers.

# ---------------------------------------------------------------------------------------------------------------
# 6. Queue depth sanity (5.3): io_uring is not available from Java, so NVMe queue depth comes only from concurrent
#    synchronous preads. QD 32 at 100 us per read = 320K IOPS (Little's law). The readers are the 32-way spork-hydrate
#    pool, up to 96 HTTP workers (read-node.properties) and 8 parallel-scan producers: keep the sum near 128.
# ---------------------------------------------------------------------------------------------------------------
# Verify under load:  iostat -x 1 "$NVME_BASENAME"   (aqu-sz near 32, r_await near 0.1 ms)

echo "os-tuning: review each block above against this node's devices before relying on it"
