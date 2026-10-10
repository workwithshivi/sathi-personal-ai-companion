
import os
import requests

from datetime import datetime, timezone
from database import get_connection

API_URL = os.environ.get(
    "TRANSCRIPTION_API_URL",
    "http://10.153.210.18:8080/api/transcripts"
)

DEVICE_ID = "raspberrypi-01"
BATCH_SIZE = 5
TIMEOUT_SECONDS = 20


def sync_batch():
    # Fetch a bounded batch of pending records.
    with get_connection() as conn:
        rows = conn.execute("""
            SELECT id, meeting_id, timestamp, text
            FROM transcriptions
            WHERE sync_status = 'pending'
            ORDER BY id
            LIMIT ?
        """, (BATCH_SIZE,)).fetchall()

    if not rows:
        print("No pending records to sync.")
        return 0

    payload = {
        "device_id": DEVICE_ID,
        "records": [dict(row) for row in rows]
    }

    try:
        response = requests.post(
            API_URL,
            json=payload,
            timeout=TIMEOUT_SECONDS
        )
        response.raise_for_status()
        result = response.json()

        acknowledged_ids = {
            int(record_id)
            for record_id in result.get("synced_ids", [])
        }
        submitted_ids = {row["id"] for row in rows}

        # Reject acknowledgements for records not in this batch.
        acknowledged_ids &= submitted_ids

        if not acknowledged_ids:
            print("API acknowledged no records.")
            return 0

        synced_at = datetime.now(
            timezone.utc
        ).isoformat(timespec="seconds")

        with get_connection() as conn:
            conn.executemany("""
                UPDATE transcriptions
                SET sync_status = 'synced',
                    synced_at = ?,
                    sync_attempts = sync_attempts + 1
                WHERE id = ? AND sync_status = 'pending'
            """, [
                (synced_at, record_id)
                for record_id in acknowledged_ids
            ])

        print(f"Synced {len(acknowledged_ids)} records.")
        return len(acknowledged_ids)

    except (requests.RequestException, ValueError) as exc:
        # Failed records stay pending and will be retried.
        with get_connection() as conn:
            conn.executemany("""
                UPDATE transcriptions
                SET sync_attempts = sync_attempts + 1
                WHERE id = ? AND sync_status = 'pending'
            """, [(row["id"],) for row in rows])

        print(f"Batch sync failed: {exc}")
        return 0


if __name__ == "__main__":
    sync_batch()
