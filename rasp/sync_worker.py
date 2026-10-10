
import os
from collections import defaultdict
from datetime import datetime, timezone

import requests

from database import get_connection

API_URL = os.environ.get(
    "TRANSCRIPTION_API_URL",
    "http://10.153.210.18:8080/ai/transcripts"
)

DEVICE_NAME = os.environ.get("TRANSCRIPTION_DEVICE", "raspberrypi-01")
BATCH_SIZE = 50
TIMEOUT_SECONDS = 20


def sync_batch():
    # Fetch a bounded batch; one cron run never drains an unlimited backlog.
    with get_connection() as conn:
        rows = conn.execute("""
            SELECT id, meeting_id, timestamp, text
            FROM transcriptions
            WHERE sync_status = 'pending'
              AND meeting_id IS NOT NULL
            ORDER BY id
            LIMIT ?
        """, (BATCH_SIZE,)).fetchall()

    if not rows:
        print("No pending records to sync.")
        return 0

    grouped_rows = defaultdict(list)
    for row in rows:
        grouped_rows[row["meeting_id"]].append(row)

    total_synced = 0

    for meeting_id, meeting_rows in grouped_rows.items():
        payload = {
            "text": [row["text"] for row in meeting_rows],
            "device": DEVICE_NAME,
            "meeting_id": meeting_id,
        }

        try:
            response = requests.post(
                API_URL,
                json=payload,
                timeout=TIMEOUT_SECONDS,
            )
            response.raise_for_status()
            result = response.json()

            if result.get("status") != "saved":
                raise ValueError(f"Unexpected API response: {result}")

            synced_at = datetime.now(
                timezone.utc
            ).isoformat(timespec="seconds")
            record_ids = [row["id"] for row in meeting_rows]
            placeholders = ",".join("?" for _ in record_ids)

            with get_connection() as conn:
                conn.execute(f"""
                    UPDATE transcriptions
                    SET sync_status = 'synced',
                        synced_at = ?,
                        sync_attempts = sync_attempts + 1
                    WHERE sync_status = 'pending'
                      AND id IN ({placeholders})
                """, [synced_at, *record_ids])

            total_synced += len(record_ids)
            print(f"Synced {len(record_ids)} records for meeting {meeting_id}.")

        except (requests.RequestException, ValueError) as exc:
            with get_connection() as conn:
                conn.executemany("""
                    UPDATE transcriptions
                    SET sync_attempts = sync_attempts + 1
                    WHERE id = ? AND sync_status = 'pending'
                """, [(row["id"],) for row in meeting_rows])

            print(f"Sync failed for meeting {meeting_id}: {exc}")

    print(f"Total records synced: {total_synced}")
    return total_synced


if __name__ == "__main__":
    sync_batch()
