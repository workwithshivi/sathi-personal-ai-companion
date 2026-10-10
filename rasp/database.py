
import sqlite3
import uuid
from datetime import datetime, timezone

DB_PATH = "/home/piuser/savan/voice_memory.db"


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def get_connection():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout = 30000")
    return conn


def init_db():
    with get_connection() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS meetings (
                meeting_id TEXT PRIMARY KEY,
                started_at TEXT NOT NULL,
                ended_at TEXT,
                status TEXT NOT NULL DEFAULT 'active'
            )
        """)

        conn.execute("""
            CREATE TABLE IF NOT EXISTS transcriptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                text TEXT NOT NULL
            )
        """)

        # Migrate an existing transcriptions table safely.
        columns = {
            row["name"]
            for row in conn.execute(
                "PRAGMA table_info(transcriptions)"
            ).fetchall()
        }

        migrations = {
            "meeting_id": "TEXT REFERENCES meetings(meeting_id)",
            "sync_status": "TEXT NOT NULL DEFAULT 'pending'",
            "synced_at": "TEXT",
            "sync_attempts": "INTEGER NOT NULL DEFAULT 0",
        }

        for column, definition in migrations.items():
            if column not in columns:
                conn.execute(
                    f"ALTER TABLE transcriptions "
                    f"ADD COLUMN {column} {definition}"
                )

        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_transcription_sync
            ON transcriptions(sync_status, id)
        """)

    print("Database initialized.")


def start_meeting():
    meeting_id = datetime.now().strftime("%Y%m%d_%H%M%S")

    with get_connection() as conn:
        conn.execute("""
            INSERT INTO meetings (meeting_id, started_at, status)
            VALUES (?, ?, 'active')
        """, (meeting_id, now()))

    print(f"Meeting started: {meeting_id}")
    return meeting_id


def end_meeting(meeting_id):
    if not meeting_id:
        return

    with get_connection() as conn:
        conn.execute("""
            UPDATE meetings
            SET ended_at = ?, status = 'ended'
            WHERE meeting_id = ? AND status = 'active'
        """, (now(), meeting_id))

    print(f"Meeting ended: {meeting_id}")


def save_transcription(meeting_id, text):
    text = text.strip()

    if not text or not meeting_id:
        return None

    with get_connection() as conn:
        cursor = conn.execute("""
            INSERT INTO transcriptions
                (meeting_id, timestamp, text, sync_status)
            VALUES (?, ?, ?, 'pending')
        """, (meeting_id, now(), text))

        record_id = cursor.lastrowid

    print(f"Saved transcription {record_id}: {text}")
    return record_id


def get_recent_transcriptions(limit=20):
    with get_connection() as conn:
        return conn.execute("""
            SELECT id, meeting_id, timestamp, text, sync_status
            FROM transcriptions
            ORDER BY id DESC
            LIMIT ?
        """, (limit,)).fetchall()


if __name__ == "__main__":
    init_db()
