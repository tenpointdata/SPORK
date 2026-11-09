#!/usr/bin/env python3
"""Create the test database and its schema on a SPORK cluster.

    Record (record_id LONG, unique)  --OBSERVED_DATA-->  Data (text STRING)

Safe to run twice: the database is created only when missing and every DDL line is IF NOT EXISTS.

    python3 create_schema.py                  # uses cluster.json next to this script
    python3 create_schema.py --config other.json --database another_db
"""
import argparse
import re
import sys

from spork_client import SporkClient, SporkError

# record_id is LONG with a UNIQUE_HASH index, the same shape as SPORK schema v1 (docs/spork/config/schema-ddl.sql),
# so a lookup by record_id is one hash probe and a duplicate upload is refused by the database itself.
SCHEMA = """
CREATE VERTEX TYPE Record IF NOT EXISTS;
CREATE PROPERTY Record.record_id IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON Record (record_id) UNIQUE_HASH;

CREATE VERTEX TYPE Data IF NOT EXISTS;
CREATE PROPERTY Data.text IF NOT EXISTS STRING (mandatory true, notnull true);

CREATE EDGE TYPE OBSERVED_DATA IF NOT EXISTS;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", help="cluster config (default: cluster.json next to this script)")
    parser.add_argument("--database", help="database name (default: 'database' from the config)")
    args = parser.parse_args()

    client, config = SporkClient.from_config(args.config)
    database = args.database or config.get("database", "spork_test")
    # The name goes into a server command string, so only allow plain names.
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", database):
        sys.exit(f"Database name '{database}' must be letters, digits and underscores, starting with a letter")

    try:
        if database in client.list_databases():
            print(f"Database '{database}' already exists, keeping it")
        else:
            client.server_command(f"create database {database}")
            print(f"Created database '{database}'")

        client.command(database, "sqlscript", SCHEMA)
        print("Schema in place:")
        for row in client.query(database, "SELECT name, type FROM schema:types WHERE name IN ['Record', 'Data', 'OBSERVED_DATA'] ORDER BY name"):
            print(f"  {row['name']:<14} {row['type']}")
    except (SporkError, ConnectionError) as e:
        sys.exit(f"Failed: {e}")


if __name__ == "__main__":
    main()
