#!/usr/bin/env python3
"""
Runs on the Raspberry Pi.
Reads unsent transcript rows from SQLite and POSTs them to the laptop.
Rows are marked sent=1 only after the laptop confirms receipt, so nothing is
lost if the network drops. Safe to run while savan.py keeps writing.

Install:  pip install requests
Run:      python3 pi_sender.py
"""
import sqlite3
import time
import logging
import requests

# ---------- CONFIG: adjust to your setup ----------
DB_PATH = "/home/pi/meeting/transcripts.db"
TABLE = "transcripts"            # your table name
ID_COLUMN = "id"                 # INTEGER PRIMARY KEY (auto-increment)
LAPTOP_URL = "http://laptop.local:8000/ingest"
API_KEY = "change-me-to-a-long-random-string"   # must match the receiver
DEVICE_ID = "pi4-meeting-room"
BATCH_SIZE = 50
POLL_SECONDS = 5
REQUEST_TIMEOUT = 10
# --------------------------------------------------

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("sender")


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")  # lets reader and writer coexist
    return conn


def ensure_sent_column(conn):
    cols = [r["name"] for r in conn.execute(f"PRAGMA table_info({TABLE})")]
    if "sent" not in cols:
        conn.execute(f"ALTER TABLE {TABLE} ADD COLUMN sent INTEGER DEFAULT 0")
        conn.execute(f"UPDATE {TABLE} SET sent = 0 WHERE sent IS NULL")
        conn.commit()
        log.info("Added 'sent' column to %s", TABLE)


def fetch_batch(conn):
    cur = conn.execute(
        f"SELECT * FROM {TABLE} WHERE sent = 0 OR sent IS NULL "
        f"ORDER BY {ID_COLUMN} LIMIT ?",
        (BATCH_SIZE,),
    )
    return [dict(r) for r in cur.fetchall()]


def send_batch(rows):
    payload = {"device_id": DEVICE_ID, "rows": rows}
    resp = requests.post(
        LAPTOP_URL,
        json=payload,
        headers={"X-API-Key": API_KEY},
        timeout=REQUEST_TIMEOUT,
    )
    resp.raise_for_status()
    return resp.json()


def mark_sent(conn, ids):
    marks = ",".join("?" * len(ids))
    conn.execute(f"UPDATE {TABLE} SET sent = 1 WHERE {ID_COLUMN} IN ({marks})", ids)
    conn.commit()


def main():
    conn = connect()
    ensure_sent_column(conn)
    backoff = POLL_SECONDS

    while True:
        try:
            rows = fetch_batch(conn)
            if not rows:
                time.sleep(POLL_SECONDS)
                continue

            result = send_batch(rows)
            mark_sent(conn, [r[ID_COLUMN] for r in rows])
            log.info("Sent %d rows (laptop stored %s)", len(rows), result.get("stored"))
            backoff = POLL_SECONDS  # reset after success
            # loop immediately in case more rows are waiting

        except requests.RequestException as e:
            # Laptop unreachable or error response: keep rows unsent, retry later
            backoff = min(backoff * 2, 60)
            log.warning("Send failed (%s). Retrying in %ds", e, backoff)
            time.sleep(backoff)
        except sqlite3.Error as e:
            log.error("SQLite error: %s", e)
            time.sleep(POLL_SECONDS)
        except KeyboardInterrupt:
            log.info("Stopping")
            break


if __name__ == "__main__":
    main()
