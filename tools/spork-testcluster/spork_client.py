"""Small HTTP client for a SPORK (ArcadeDB) test cluster. Python 3.8+ standard library only.

Shared by create_schema.py and record_io.py. It reads cluster.json, asks for the root password once
(or takes SPORK_PASSWORD), and sends every request to the first node that answers, moving on to the
next node when one is down. Any node accepts a write: a follower forwards it to the Raft leader.
"""
import base64
import getpass
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

DEFAULT_CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cluster.json")


class SporkError(Exception):
    """The server answered, and the answer was an error (bad query, duplicate key, auth...)."""

    def __init__(self, status, detail):
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status
        self.detail = detail


def node_setting(config, node, key, default=None):
    """A per-node value wins over the cluster-wide one (handy when two nodes share a host)."""
    return node.get(key, config.get(key, default))


def load_config(path=None):
    path = path or os.environ.get("SPORK_CLUSTER_CONFIG") or DEFAULT_CONFIG
    if not os.path.exists(path):
        sys.exit(f"No cluster config at {path}. Copy cluster.example.json to cluster.json and edit it.")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def node_urls(config):
    if config.get("client_urls"):
        return list(config["client_urls"])
    return [f"http://{n['host']}:{node_setting(config, n, 'http_port', 2480)}" for n in config["nodes"]]


def read_password():
    password = os.environ.get("SPORK_PASSWORD")
    if not password:
        password = getpass.getpass("SPORK root password: ")
    return password


class SporkClient:

    def __init__(self, urls, password, user="root", timeout=30, retry_seconds=30):
        self.urls = list(urls)
        self.timeout = timeout
        self.retry_seconds = retry_seconds
        self.last_url = None  # the node that answered the last request
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        self.headers = {"Authorization": f"Basic {token}", "Content-Type": "application/json"}

    @classmethod
    def from_config(cls, config_path=None):
        config = load_config(config_path)
        return cls(node_urls(config), read_password()), config

    # -- low level ---------------------------------------------------------------------------------

    def request(self, method, path, body=None, url=None):
        """Send to one node (url) or to the first node that answers. Returns (node_url, parsed JSON).

        While the cluster has no leader (a few seconds after the leader dies) nodes answer 503, so the request is
        retried for up to retry_seconds. Only failures that cannot have applied a write are retried: a refused
        connection or a 503. A timeout or 502/504 on a write is ambiguous (the write may have committed), so it is
        reported instead of being sent twice. Reads are always safe to retry.
        """
        targets = [url] if url else self.urls
        data = json.dumps(body).encode("utf-8") if body is not None else None
        is_read = method == "GET" or path.startswith("/api/v1/query/")
        deadline = time.monotonic() + self.retry_seconds
        last_error = None
        while True:
            for base in targets:
                req = urllib.request.Request(base + path, data=data, headers=self.headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                        raw = resp.read()
                        self.last_url = base
                        return base, (json.loads(raw) if raw else {})
                except urllib.error.HTTPError as e:
                    detail = e.read().decode("utf-8", "replace")
                    try:
                        parsed = json.loads(detail)
                        detail = parsed.get("detail") or parsed.get("error") or detail
                    except ValueError:
                        pass
                    if e.code == 503 or (is_read and e.code in (502, 504)):
                        last_error = SporkError(e.code, detail)
                        continue
                    raise SporkError(e.code, detail) from None
                except (TimeoutError, socket.timeout) as e:
                    if not is_read:
                        raise ConnectionError(f"{base} timed out after {self.timeout} s; the write MAY have been "
                                              "applied, check with a download before retrying") from None
                    last_error = e
                except (urllib.error.URLError, ConnectionError, OSError) as e:
                    if isinstance(getattr(e, "reason", None), socket.timeout) and not is_read:
                        raise ConnectionError(f"{base} timed out after {self.timeout} s; the write MAY have been "
                                              "applied, check with a download before retrying") from None
                    last_error = e
                    if url:
                        # One named node that is down will not come back by waiting: report it now.
                        raise ConnectionError(f"{base} is not answering: {e}") from None
            if time.monotonic() >= deadline:
                raise ConnectionError(f"No node answered ({', '.join(targets)}): {last_error}")
            time.sleep(1)

    # -- helpers -----------------------------------------------------------------------------------

    def server_command(self, command):
        return self.request("POST", "/api/v1/server", {"command": command})[1]

    def list_databases(self):
        return self.request("GET", "/api/v1/databases")[1].get("result", [])

    def command(self, database, language, command, params=None, url=None):
        body = {"language": language, "command": command}
        if params:
            body["params"] = params
        return self.request("POST", f"/api/v1/command/{database}", body, url=url)[1].get("result", [])

    def query(self, database, command, params=None, url=None):
        body = {"language": "sql", "command": command}
        if params:
            body["params"] = params
        return self.request("POST", f"/api/v1/query/{database}", body, url=url)[1].get("result", [])

    def cluster_status(self, url=None):
        return self.request("GET", "/api/v1/cluster", url=url)[1]
