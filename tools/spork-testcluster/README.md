# SPORK test cluster

Scripts to install SPORK on a few VMs as a Raft cluster, create a small test schema, and upload and download objects
from another machine. Python 3.8+ standard library only, nothing to `pip install`.

```
Record (record_id LONG, unique)  --OBSERVED_DATA-->  Data (text STRING)
```

**Test data only.** This setup sends traffic between nodes and to the HTTP API unencrypted, and uses the `root`
account. Do not load real case data on it. The production setup (LUKS, TLS/mTLS, per-user accounts) is HANDOFF open
work item 3 (WS1).

| File | Runs on | What it does |
|---|---|---|
| `cluster.example.json` | | Template for `cluster.json`: node names, IPs, SSH targets, ports |
| `deploy_nodes.py` | the machine that can SSH into the VMs | Copies the package to every node, installs it, starts it, waits for a leader |
| `node_install.sh` | each VM (copied there by `deploy_nodes.py`) | Checks Java, unpacks, writes the settings and a systemd unit, starts the node |
| `create_schema.py` | the client server | Creates the database and the schema above (safe to re-run) |
| `record_io.py` | the client server | `upload`, `download`, `roundtrip` of one Record --OBSERVED_DATA--> Data |
| `spork_client.py` | (library) | HTTP client shared by the two scripts above: fails over between nodes, retries through leader elections |

## 1. Build the package (once, on any machine with Java 21 and Maven)

```bash
mvn -DskipTests -pl package -am install
```

The result is `package/target/arcadedb-26.11.1-SNAPSHOT-base.tar.gz` (about 90 MB, enough for this).

## 2. Prepare the VMs

On every VM:

- Java 21 or newer (`sudo apt install openjdk-21-jre-headless`, or `sudo dnf install java-21-openjdk-headless`).
- SSH access from the machine you deploy from, as root or as a user with passwordless sudo.
- Firewall: open TCP **2480** (HTTP API, from the client server and the other nodes) and **2434** (Raft, between the
  nodes only).
- 3 nodes (or 5). Raft needs a majority to accept writes: 3 nodes survive 1 failure, 2 nodes survive none.

## 3. Deploy

On the machine that can SSH into the VMs, with this folder and the tarball copied there:

```bash
cp cluster.example.json cluster.json     # then edit the names, IPs and ssh targets
python3 deploy_nodes.py --tarball arcadedb-26.11.1-SNAPSHOT-base.tar.gz --sudo
```

Leave out `--sudo` when the SSH user is root. It asks for the root password of the database (8+ characters) once; set
`SPORK_PASSWORD` to skip the prompt. The end of the output looks like:

```
  http://10.0.0.11:2480: LEADER    leader=10.0.0.11:2480  applied=6
  http://10.0.0.12:2480: follower  leader=10.0.0.11:2480  applied=5
  http://10.0.0.13:2480: follower  leader=10.0.0.11:2480  applied=5
```

Each node is installed in `/opt/spork` as the systemd service `spork-<name>` (`journalctl -u spork-n0 -f` for its
log, `sudo systemctl stop spork-n0` to stop it). Re-running the deploy upgrades `bin/` and `lib/` and keeps the data.
`python3 deploy_nodes.py --status` shows the cluster at any time.

The root password is only set the first time a node starts. Changing it later is done through the server, not by
re-running the deploy.

## 4. Create the schema (from the client server)

Copy `cluster.json`, `spork_client.py`, `create_schema.py` and `record_io.py` to the client server, then:

```bash
python3 create_schema.py
```

## 5. Upload and download

```bash
python3 record_io.py upload --record-id 1001 --text "first sighting"
```
```bash
python3 record_io.py download --record-id 1001 --all-nodes
```
```bash
python3 record_io.py roundtrip
```

`--all-nodes` asks every node separately, so you can see the object replicated. `roundtrip` uploads a new object and
times how long each node takes to show it. Add `--node http://10.0.0.12:2480` before the action to use one node only.

## If the client server cannot reach the VMs directly

When only the SSH host can reach the VMs, tunnel through it from the client server, one local port per node:

```bash
ssh -N -L 12480:10.0.0.11:2480 -L 12481:10.0.0.12:2480 -L 12482:10.0.0.13:2480 you@ssh-host
```

and set `"client_urls": ["http://127.0.0.1:12480", "http://127.0.0.1:12481", "http://127.0.0.1:12482"]` in the
client's `cluster.json`. The tunnel also encrypts the traffic between the client and the SSH host.

## What the scripts take care of

- **Leader elections.** For a few seconds after the leader dies the cluster has no leader and answers 503. The client
  retries for up to 30 s, moving between nodes, so an upload during an election just takes longer.
- **No double writes.** Only failures that cannot have applied a write are retried (connection refused, 503). A timeout
  on a write is reported as "MAY have been applied" rather than sent again.
- **One transaction per upload.** Record, Data and the edge are written together or not at all. A duplicate
  `record_id` is refused by the unique index and leaves nothing behind.
- **Parameters, not string building.** Uploaded text is passed as a query parameter, so quotes or SQL in the text are
  stored as text.
- **Password handling.** The password goes to each node over SSH stdin into a `0600` file read via
  `arcadedb.server.rootPasswordPath`, so it never appears on a command line or in `ps`.
- **JMX off.** The stock `bin/server.sh` opens JMX on ports 9999/9998 with no authentication, which lets anyone who
  can reach the VM run code in the JVM. `node_install.sh` switches it off.

## Ideas for testing

- Kill the leader in the middle of a loop of uploads (`sudo systemctl kill -s KILL spork-n0`), then
  `record_io.py download --all-nodes` to check nothing was lost or duplicated.
- Stop one node, upload, start it again, and download from it: it catches up from the others.
- `deploy_nodes.py` restarts the nodes one after another without waiting for each to rejoin. That is fine for a test
  cluster, but it is not a zero-downtime rolling upgrade.

## How this was tested

On 2026-11-09, on one machine with three nodes on separate ports and `ssh` / `scp` replaced by local stand-ins:
deploy, schema (twice), upload through a follower, download from every node, duplicate refused with nothing left
behind, text containing quotes and SQL stored verbatim, roundtrip, a write during a leader election (succeeded after
6.7 s), a node killed and caught up on re-deploy, an upgrade keeping the data, password absent from `ps`, nothing
listening on 9998/9999. Not yet tested: real SSH, real VMs, systemd (the container had none, so the fallback start was
used).
