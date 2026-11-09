#!/usr/bin/env python3
"""Install SPORK on every node in cluster.json over SSH, start it, and wait for the cluster to elect a leader.

Run it on a machine that can SSH into every VM (e.g. the server that hosts them). It uses the system `ssh` and `scp`
commands, so your existing SSH keys and ~/.ssh/config apply. Nothing to pip install.

    python3 deploy_nodes.py --tarball arcadedb-26.11.1-SNAPSHOT-base.tar.gz
    python3 deploy_nodes.py --tarball ... --sudo          # SSH user is not root but has passwordless sudo
    python3 deploy_nodes.py --status                      # just show the cluster state

The root password is asked for once (or read from SPORK_PASSWORD) and piped to each node over SSH stdin, so it never
appears on a command line. Re-running it upgrades the nodes in place and keeps their data.
"""
import argparse
import os
import shlex
import subprocess
import sys
import time

from spork_client import SporkClient, load_config, node_setting, node_urls, read_password

HERE = os.path.dirname(os.path.abspath(__file__))


def server_list(config):
    """name@host:raftPort:httpPort for every node, the same string on every node."""
    return ",".join(
        f"{n['name']}@{n['host']}:{node_setting(config, n, 'raft_port', 2434)}:{node_setting(config, n, 'http_port', 2480)}"
        for n in config["nodes"])


def run(cmd, stdin_text=None):
    result = subprocess.run(cmd, input=stdin_text, text=True, capture_output=True)
    for line in (result.stdout + result.stderr).splitlines():
        print(f"    {line}")
    if result.returncode != 0:
        raise RuntimeError(f"'{' '.join(cmd[:2])} ...' exited with {result.returncode}")


def deploy_node(config, node, tarball, password, use_sudo):
    target = node["ssh"]
    remote_tar = f"/tmp/spork-{node['name']}.tar.gz"
    remote_script = f"/tmp/spork-node-install-{node['name']}.sh"

    print(f"[{node['name']}] copying the package to {target}")
    run(["scp", "-q", tarball, f"{target}:{remote_tar}"])
    run(["scp", "-q", os.path.join(HERE, "node_install.sh"), f"{target}:{remote_script}"])

    args = [
        "bash", remote_script,
        "--tarball", remote_tar,
        "--name", node["name"],
        "--server-list", server_list(config),
        "--raft-port", str(node_setting(config, node, "raft_port", 2434)),
        "--http-port", str(node_setting(config, node, "http_port", 2480)),
        "--install-dir", node_setting(config, node, "install_dir", "/opt/spork"),
        "--heap", node_setting(config, node, "heap", "2g"),
    ]
    remote = " ".join(shlex.quote(a) for a in args)
    if use_sudo:
        remote = "sudo -n " + remote   # -n: fail fast instead of hanging on a sudo password prompt
    remote += f"; rc=$?; rm -f {shlex.quote(remote_script)}; exit $rc"

    print(f"[{node['name']}] installing and starting")
    run(["ssh", target, remote], stdin_text=password + "\n")


def wait_for_cluster(config, password, timeout):
    client = SporkClient(node_urls(config), password)
    deadline = time.monotonic() + timeout
    print(f"Waiting up to {timeout} s for every node to be ready and a leader to be elected...")
    while time.monotonic() < deadline:
        try:
            status = client.cluster_status()
            leader = status.get("leaderHttpAddress")
            ready = []
            for url in client.urls:
                try:
                    client.request("GET", "/api/v1/ready", url=url)
                    ready.append(url)
                except Exception:
                    pass
            if leader and len(ready) == len(client.urls):
                print_status(client)
                return True
        except Exception:
            pass
        time.sleep(2)
    print("The cluster did not come up in time. Check the node logs (journalctl -u spork-<name>, or <install_dir>/server.out).")
    print_status(client)
    return False


def print_status(client):
    for url in client.urls:
        try:
            s = client.cluster_status(url=url)
            role = "LEADER" if s.get("isLeader") else "follower"
            print(f"  {url}: {role:<8}  leader={s.get('leaderHttpAddress')}  applied={s.get('localAppliedIndex')}")
        except Exception as e:
            print(f"  {url}: DOWN ({e.__class__.__name__})")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", help="cluster config (default: cluster.json next to this script)")
    parser.add_argument("--tarball", help="the SPORK package, e.g. package/target/arcadedb-26.11.1-SNAPSHOT-base.tar.gz")
    parser.add_argument("--sudo", action="store_true", help="run the install with 'sudo -n' (SSH user is not root)")
    parser.add_argument("--status", action="store_true", help="only show the cluster state")
    parser.add_argument("--wait", type=int, default=180, help="seconds to wait for the cluster after starting")
    args = parser.parse_args()

    config = load_config(args.config)
    if len(config["nodes"]) % 2 == 0:
        print(f"WARNING: {len(config['nodes'])} nodes. Raft needs a majority to accept writes, so an even count "
              "tolerates no more failures than one node fewer. 3 or 5 is the usual choice.")

    password = read_password()
    if args.status:
        print_status(SporkClient(node_urls(config), password))
        return
    if not args.tarball or not os.path.isfile(args.tarball):
        sys.exit("--tarball must point at the package built with: mvn -DskipTests -pl package -am install")

    for node in config["nodes"]:
        try:
            deploy_node(config, node, args.tarball, password, args.sudo)
        except RuntimeError as e:
            sys.exit(f"[{node['name']}] FAILED: {e}")

    sys.exit(0 if wait_for_cluster(config, password, args.wait) else 1)


if __name__ == "__main__":
    main()
