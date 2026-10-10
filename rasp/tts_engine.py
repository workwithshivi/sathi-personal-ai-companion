
from pathlib import Path
import subprocess
import threading

from piper import PiperVoice


BASE_DIR = Path(__file__).resolve().parent
MODEL_DIR = BASE_DIR / "tts" / "models"

MODEL_PATH = MODEL_DIR / "en_US-lessac-medium.onnx"

_voice = None
_voice_lock = threading.Lock()


def load_voice():
    """Load the TTS model once and reuse it."""
    global _voice

    if _voice is None:
        if not MODEL_PATH.exists():
            raise FileNotFoundError(
                f"TTS model not found: {MODEL_PATH}"
            )

        _voice = PiperVoice.load(str(MODEL_PATH))

    return _voice


def speak(text):
    """Synthesize text and play it locally."""

    text = text.strip()
    if not text:
        return

    voice = load_voice()

    # Keep synthesis and playback serialized.
    with _voice_lock:
        with subprocess.Popen(
            [
                "aplay",
                "-q",
                "-t", "raw",
                "-f", "S16_LE",
                "-c", "1",
                "-r", str(voice.config.sample_rate),
                "-",
            ],
            stdin=subprocess.PIPE,
        ) as player:

            try:
                for chunk in voice.synthesize(text):
                    if player.stdin is None:
                        raise RuntimeError("Audio output unavailable")

                    player.stdin.write(chunk.audio_int16_bytes)

                player.stdin.close()
                player.stdin = None

                if player.wait() != 0:
                    raise RuntimeError("Audio playback failed")

            except Exception:
                if player.poll() is None:
                    player.kill()
                    player.wait()
                raise


if __name__ == "__main__":
    speak(
        "Hello! Your Raspberry Pi voice assistant is working."
    )
