
from collections import defaultdict

from api_client import upload_transcripts
from database import get_connection


BATCH_SIZE = 50


def sync_batch():
    """
    Upload pending transcriptions in batches grouped by meeting.
    Failed batches remain pending for retry.
    """

    # Fetch a bounded set of pending records.
    with get_connection() as conn:
        rows = conn.execute("""
            SELECT id, meeting_id, timestamp, text
            FROM transcriptions
            WHERE sync_status = 'pending'
              AND meeting_id IS NOT NULL
            ORDER BY id ASC
            LIMIT ?
        """, (BATCH_SIZE,)).fetchall()

    if not rows:
        print("No pending transcriptions to sync.")
        return 0

    # Group by meeting because each API request has one meeting_id.
    grouped = defaultdict(list)

    for row in rows:
        grouped[row["meeting_id"]].append(row)

    total_synced = 0

    for meeting_id, meeting_rows in grouped.items():

        ids = [row["id"] for row in meeting_rows]
        texts = [row["text"] for row in meeting_rows]

        try:
            result = upload_transcripts(
                meeting_id=meeting_id,
                texts=texts,
            )

            print(
                f"API accepted upload for meeting {meeting_id}: "
                f"{result}"
            )

            # Only mark records synced after a successful HTTP response.
            # If the API uses a different success contract, adjust here.
            from datetime import datetime, timezone

            synced_at = datetime.now(
                timezone.utc
            ).isoformat(timespec="seconds")

            with get_connection() as conn:
                placeholders = ",".join("?" for _ in ids)

                conn.execute(f"""
                    UPDATE transcriptions
                    SET sync_status = 'synced',
                        synced_at = ?,
                        sync_attempts = sync_attempts + 1
                    WHERE sync_status = 'pending'
                      AND id IN ({placeholders})
                """, [synced_at, *ids])

            total_synced += len(ids)

            print(
                f"Synced {len(ids)} records "
                f"for meeting {meeting_id}."
            )

        except Exception as exc:
            # Leave records pending so a later run retries them.
            with get_connection() as conn:
                conn.executemany("""
                    UPDATE transcriptions
                    SET sync_attempts = sync_attempts + 1
                    WHERE id = ? AND sync_status = 'pending'
                """, [(record_id,) for record_id in ids])

            print(
                f"Sync failed for meeting {meeting_id}: {exc}"
            )

    print(f"Total records synced: {total_synced}")
    return total_synced


if __name__ == "__main__":
    sync_batch()
