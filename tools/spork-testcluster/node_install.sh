#!/usr/bin/env bash
# Installs (or upgrades) one SPORK node and starts it. Run ON the node, normally by deploy_nodes.py over SSH.
# The root password arrives on stdin so it never shows on a command line or in `ps`.
#
#   node_install.sh --tarball /tmp/spork.tar.gz --name n0 --server-list 'n0@10.0.0.11:2434:2480,...' \
#                   [--raft-port 2434] [--http-port 2480] [--install-dir /opt/spork] [--heap 2g] < password
#
# Needs: Java 21+, tar, and root (or sudo) to create the service user and the systemd unit.
# Upgrades keep databases/, config/ and the Raft state: only bin/ and lib/ are replaced.
set -euo pipefail

RAFT_PORT=2434
HTTP_PORT=2480
INSTALL_DIR=/opt/spork
HEAP=2g
TARBALL="" NAME="" SERVER_LIST=""

while [ $# -gt 0 ]; do
  case "$1" in
    --tarball) TARBALL="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --server-list) SERVER_LIST="$2"; shift 2 ;;
    --raft-port) RAFT_PORT="$2"; shift 2 ;;
    --http-port) HTTP_PORT="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --heap) HEAP="$2"; shift 2 ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done
[ -n "$TARBALL" ] && [ -n "$NAME" ] && [ -n "$SERVER_LIST" ] || { echo "--tarball, --name and --server-list are required" >&2; exit 2; }
[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "--name must be letters, digits, '-' or '_'" >&2; exit 2; }

say() { echo "[$NAME] $*"; }

# 1. Java 21 or newer
if ! command -v java >/dev/null 2>&1; then
  echo "[$NAME] Java is not installed. Install Java 21+ first, e.g. 'sudo apt install openjdk-21-jre-headless' (Debian/Ubuntu)" >&2
  echo "[$NAME] or 'sudo dnf install java-21-openjdk-headless' (RHEL/Rocky/Fedora)." >&2
  exit 3
fi
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "[$NAME] Java $JAVA_MAJOR found, SPORK needs 21 or newer." >&2
  exit 3
fi
say "Java $JAVA_MAJOR OK"

# 2. Password from stdin
IFS= read -r PASSWORD || true
[ -n "$PASSWORD" ] || { echo "[$NAME] No root password on stdin" >&2; exit 2; }
[ ${#PASSWORD} -ge 8 ] || { echo "[$NAME] The root password must be at least 8 characters" >&2; exit 2; }

# 3. Service user (only when running as root; otherwise the node runs as the SSH user)
RUN_USER=$(id -un)
if [ "$(id -u)" -eq 0 ]; then
  id spork >/dev/null 2>&1 || useradd --system --home-dir "$INSTALL_DIR" --shell /usr/sbin/nologin spork
  RUN_USER=spork
fi

# 4. Unpack. A fresh install takes everything; an upgrade replaces only bin/ and lib/.
mkdir -p "$INSTALL_DIR"
STAGE=$(mktemp -d)
tar xzf "$TARBALL" -C "$STAGE" --strip-components=1
if [ -d "$INSTALL_DIR/lib" ]; then
  say "Upgrading bin/ and lib/ in $INSTALL_DIR (databases and config kept)"
  rm -rf "$INSTALL_DIR/lib" "$INSTALL_DIR/bin"
  cp -a "$STAGE/lib" "$STAGE/bin" "$INSTALL_DIR/"
else
  say "Installing into $INSTALL_DIR"
  cp -a "$STAGE/." "$INSTALL_DIR/"
fi
rm -rf "$STAGE"

# 5. Password file, readable by the service user only
mkdir -p "$INSTALL_DIR/secrets"
umask 077
printf '%s' "$PASSWORD" > "$INSTALL_DIR/secrets/root-password"
umask 022

# 6. Settings. JMX is switched OFF: the stock server.sh opens it on 9999/9998 with no authentication, which would let
#    anyone who reaches the VM run code in the JVM.
cat > "$INSTALL_DIR/spork.env" <<EOF
ARCADEDB_JMX="-Dcom.sun.management.jmxremote=false"
ARCADEDB_OPTS_MEMORY="-Xms$HEAP -Xmx$HEAP"
ARCADEDB_SETTINGS="-Darcadedb.server.name=$NAME -Darcadedb.server.rootPasswordPath=$INSTALL_DIR/secrets/root-password -Darcadedb.ha.enabled=true -Darcadedb.ha.serverList=$SERVER_LIST -Darcadedb.ha.raftPort=$RAFT_PORT -Darcadedb.server.httpIncomingPort=$HTTP_PORT"
EOF
[ "$(id -u)" -eq 0 ] && chown -R "$RUN_USER": "$INSTALL_DIR"

# 7. Start: systemd when it is running, plain background process otherwise
SERVICE="spork-$NAME"
if [ "$(id -u)" -eq 0 ] && [ -d /run/systemd/system ]; then
  cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=SPORK node $NAME
After=network-online.target
Wants=network-online.target

[Service]
User=$RUN_USER
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$INSTALL_DIR/spork.env
ExecStart=$INSTALL_DIR/bin/server.sh
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable "$SERVICE" >/dev/null
  systemctl restart "$SERVICE"
  say "Started systemd service $SERVICE (logs: journalctl -u $SERVICE -f)"
else
  if [ -f "$INSTALL_DIR/spork.pid" ] && kill -0 "$(cat "$INSTALL_DIR/spork.pid")" 2>/dev/null; then
    kill "$(cat "$INSTALL_DIR/spork.pid")"; sleep 3
  fi
  cd "$INSTALL_DIR"
  set -a; . "$INSTALL_DIR/spork.env"; set +a
  nohup "$INSTALL_DIR/bin/server.sh" > "$INSTALL_DIR/server.out" 2>&1 &
  echo $! > "$INSTALL_DIR/spork.pid"
  say "Started without systemd, pid $(cat "$INSTALL_DIR/spork.pid") (logs: $INSTALL_DIR/server.out)"
fi
rm -f "$TARBALL"
