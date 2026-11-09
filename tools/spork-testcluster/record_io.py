#!/usr/bin/env python3
"""Upload and download one object on a SPORK cluster: a Record, its Data, and the OBSERVED_DATA edge between them.

    python3 record_io.py upload    --record-id 1001 --text "first sighting"
    python3 record_io.py download  --record-id 1001
    python3 record_io.py download  --record-id 1001 --all-nodes     # ask every node, to see replication
    python3 record_io.py roundtrip                                  # upload a new object, then read it back from every node

The upload is one transaction: the Record, the Data and the edge are all written, or none is. A second upload with the
same record_id is refused by the unique index on Record.record_id. Values are sent as query parameters, never pasted
into the SQL text, so any text is safe to upload.
"""
import argparse
import json
import sys
import time

from spork_client import SporkClient, SporkError

UPLOAD = """
BEGIN;
LET record = INSERT INTO Record SET record_id = :record_id;
LET data = INSERT INTO Data SET text = :text;
CREATE EDGE OBSERVED_DATA FROM $record TO $data;
COMMIT RETRY 3;
"""

# One hash-index probe on record_id, then a hop along OBSERVED_DATA.
DOWNLOAD = """
SELECT record_id, @rid AS record_rid,
       out('OBSERVED_DATA').@rid AS data_rids,
       out('OBSERVED_DATA').text AS texts
FROM Record WHERE record_id = :record_id
"""


def upload(client, database, record_id, text, url=None):
    client.command(database, "sqlscript", UPLOAD, {"record_id": record_id, "text": text}, url=url)


def download(client, database, record_id, url=None):
    """The object as a dict, or None when that node has no Record with this record_id."""
    rows = client.query(database, DOWNLOAD, {"record_id": record_id}, url=url)
    if not rows:
        return None
    row = rows[0]
    return {
        "record_id": row["record_id"],
        "record_rid": row["record_rid"],
        "data": [{"rid": rid, "text": text} for rid, text in zip(row.get("data_rids", []), row.get("texts", []))],
    }


def cmd_upload(client, database, args):
    upload(client, database, args.record_id, args.text, url=args.node)
    print(f"Uploaded Record {args.record_id} --OBSERVED_DATA--> Data")


def cmd_download(client, database, args):
    targets = client.urls if args.all_nodes else [args.node]
    found = False
    for url in targets:
        try:
            obj = download(client, database, args.record_id, url=url)
        except ConnectionError as e:
            print(f"{url or 'cluster'}: DOWN ({e})")
            continue
        label = url or "cluster"
        if obj is None:
            print(f"{label}: no Record with record_id {args.record_id}")
        else:
            found = True
            print(f"{label}: {json.dumps(obj, ensure_ascii=False)}")
    if not found:
        sys.exit(1)


def cmd_roundtrip(client, database, args):
    record_id = args.record_id if args.record_id is not None else int(time.time() * 1000)
    text = args.text or f"roundtrip test written at {time.strftime('%Y-%m-%d %H:%M:%S')}"

    started = time.monotonic()
    upload(client, database, record_id, text, url=args.node)
    print(f"Uploaded record_id {record_id} through {client.last_url} ({(time.monotonic() - started) * 1000:.0f} ms)")

    # Followers apply the write a moment after the leader commits it, so poll each node until it shows up.
    failed = False
    for url in client.urls:
        deadline = time.monotonic() + args.wait
        polled_from = time.monotonic()
        while True:
            try:
                obj = download(client, database, record_id, url=url)
            except ConnectionError:
                print(f"  {url}: DOWN")
                failed = True
                break
            if obj is not None:
                ok = obj["data"] and obj["data"][0]["text"] == text
                failed |= not ok
                print(f"  {url}: {'OK  ' if ok else 'BAD '} visible after {(time.monotonic() - polled_from) * 1000:.0f} ms"
                      f"  {json.dumps(obj, ensure_ascii=False)}")
                break
            if time.monotonic() > deadline:
                print(f"  {url}: NOT VISIBLE after {args.wait} s")
                failed = True
                break
            time.sleep(0.1)
    if failed:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", help="cluster config (default: cluster.json next to this script)")
    parser.add_argument("--database", help="database name (default: 'database' from the config)")
    parser.add_argument("--node", help="send to this node URL only, e.g. http://10.0.0.12:2480 (default: first node that answers)")
    sub = parser.add_subparsers(dest="action", required=True)

    p = sub.add_parser("upload", help="write one Record --OBSERVED_DATA--> Data")
    p.add_argument("--record-id", type=int, required=True)
    p.add_argument("--text", required=True)

    p = sub.add_parser("download", help="read one Record and its Data")
    p.add_argument("--record-id", type=int, required=True)
    p.add_argument("--all-nodes", action="store_true", help="ask every node and show what each one has")

    p = sub.add_parser("roundtrip", help="upload a new object, then read it back from every node")
    p.add_argument("--record-id", type=int, help="default: the current time in milliseconds")
    p.add_argument("--text")
    p.add_argument("--wait", type=float, default=10.0, help="seconds to wait for each node to show the object")

    args = parser.parse_args()
    client, config = SporkClient.from_config(args.config)
    database = args.database or config.get("database", "spork_test")

    try:
        {"upload": cmd_upload, "download": cmd_download, "roundtrip": cmd_roundtrip}[args.action](client, database, args)
    except (SporkError, ConnectionError) as e:
        if isinstance(e, SporkError) and "duplicate" in str(e.detail).lower():
            sys.exit(f"Failed: record_id {args.record_id} already exists (Record.record_id is unique)")
        sys.exit(f"Failed: {e}")


if __name__ == "__main__":
    main()
