
import sqlite3
import subprocess
import sys
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = "/home/piuser/sathi-personal-ai-companion/rasp/voice_memory.db"
SYNC_PENDING_THRESHOLD = 10
SYNC_LOCK_PATH = "/tmp/sathi-sync.lock"

_sync_process = None
_sync_process_lock = threading.Lock()


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
    meeting_id = datetime.now().strftime("%Y%m%d")

    with get_connection() as conn:
        conn.execute("""
            INSERT INTO meetings (meeting_id, started_at, status)
            VALUES (?, ?, 'active')
            ON CONFLICT(meeting_id) DO UPDATE SET
                started_at = excluded.started_at,
                ended_at = NULL,
                status = 'active'
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
        pending_count = conn.execute("""
            SELECT COUNT(*)
            FROM transcriptions
            WHERE sync_status = 'pending'
        """).fetchone()[0]

    print(f"Saved transcription {record_id}: {text}")

    if pending_count >= SYNC_PENDING_THRESHOLD:
        _start_sync_worker()

    return record_id


def _start_sync_worker():
    global _sync_process

    worker_path = Path(__file__).resolve().with_name("sync_worker.py")
    log_path = worker_path.with_name("sync_worker.log")

    with _sync_process_lock:
        if _sync_process is not None and _sync_process.poll() is None:
            return

        try:
            with log_path.open("a", encoding="utf-8") as log_file:
                _sync_process = subprocess.Popen(
                    [
                        "/usr/bin/flock",
                        "-n",
                        SYNC_LOCK_PATH,
                        sys.executable,
                        str(worker_path),
                    ],
                    cwd=str(worker_path.parent),
                    stdout=log_file,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
        except OSError as exc:
            print(f"Could not start background transcript sync: {exc}")


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
