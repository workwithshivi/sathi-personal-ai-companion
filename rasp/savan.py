
import json
import signal
import re
import subprocess
import sys
import time
import unicodedata
from collections import deque
from pathlib import Path

from gpiozero import LED
import numpy as np
import sherpa_onnx
from vosk import KaldiRecognizer, Model

from database import (
    init_db,
    start_meeting,
    end_meeting,
    save_transcription,
)


# ============================================================
# CONFIGURATION
# ============================================================

GPIO_PIN = 17

MIC_TARGET = "90"

SAMPLE_RATE = 16000
CHANNELS = 1

MODEL_DIR = Path(__file__).resolve().parent / "models" / "hi-hinglish-swift"
MODEL_FILES = (
    MODEL_DIR / "encoder.int8.onnx",
    MODEL_DIR / "decoder.int8.onnx",
    MODEL_DIR / "tokens.txt",
)
VOSK_MODEL_DIR = Path(__file__).resolve().parent / "vosk-model-small-en-us-0.15"

WAKE_WORDS = [
    "hey savan",
    "hey saavan",
    "hey seven",
    "hey pie",
    "hey pi",
    "hi pi",
]

STOP_PHRASES = [
    "stop listening",
    "end meeting",
    "finish meeting",
    "close session",
    "stop recording",
    "end the meeting",
    "finish the meeting",
    "close the session",
]

# PipeWire recording format:
# 16-bit signed PCM, 16 kHz, mono.
AUDIO_CHUNK_SIZE = 4000
SPEECH_THRESHOLD = 0.005
SILENCE_CHUNKS = 6
MAX_UTTERANCE_CHUNKS = 160
SQLITE_CHUNK_SECONDS = 10.0


# ============================================================
# INITIALIZATION
# ============================================================

led = LED(GPIO_PIN)

meeting_id = None
wake_mode = True
command_mode = False

mic_process = None
running = True
transcript_buffer = []
transcript_buffer_seconds = 0.0


def print_status(message):
    print(f"\n[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def normalize_text(text):
    text = unicodedata.normalize("NFKC", text).casefold()
    return " ".join(re.findall(r"[\w]+", text, flags=re.UNICODE))


def contains_phrase(text, phrases):
    normalized_text = f" {normalize_text(text)} "
    return any(
        f" {normalize_text(phrase)} " in normalized_text
        for phrase in phrases
    )


# ============================================================
# AUDIO PROCESS
# ============================================================

def start_microphone():
    """
    Start the Bluetooth microphone using PipeWire.

    MIC_TARGET must match the PipeWire node available
    on this Raspberry Pi.
    """

    command = [
        "pw-record",
        "--target",
        MIC_TARGET,
        "--format",
        "s16",
        "--rate",
        str(SAMPLE_RATE),
        "--channels",
        str(CHANNELS),
        "-",
    ]


    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=None,
        bufsize=0,
    )

    # Give pw-record a moment to initialize.
    time.sleep(1)

    if process.poll() is not None:
        raise RuntimeError(
            "pw-record exited unexpectedly. "
            "Check the Bluetooth microphone and PipeWire."
        )

    return process


# ============================================================
# SHERPA-ONNX RECOGNIZER
# ============================================================

def create_wake_recognizer(model):
    grammar = json.dumps(WAKE_WORDS + ["[unk]"])
    return KaldiRecognizer(model, SAMPLE_RATE, grammar)


def create_recognizer():
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(MODEL_DIR / "encoder.int8.onnx"),
        decoder=str(MODEL_DIR / "decoder.int8.onnx"),
        tokens=str(MODEL_DIR / "tokens.txt"),
        num_threads=2,
        decoding_method="greedy_search",
        language="en",
        task="transcribe",
    )


def transcribe_audio(recognizer, audio_bytes):
    samples = np.frombuffer(audio_bytes, dtype="<i2")
    audio = samples.astype(np.float32) / 32768.0

    if audio.size == 0 or np.max(np.abs(audio)) < SPEECH_THRESHOLD:
        return ""

    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, audio)
    recognizer.decode_stream(stream)

    return stream.result.text.strip().lower()


# ============================================================
# SESSION MANAGEMENT
# ============================================================

def begin_session():
    global meeting_id
    global wake_mode
    global command_mode

    meeting_id = start_meeting()

    wake_mode = False
    command_mode = True

    led.on()

    print_status("WAKE WORD DETECTED")
    print_status(f"Meeting ID: {meeting_id}")
    print_status("LED ON")
    print_status("Listening to unrestricted speech.")
    print_status(
        "Say 'stop listening' or 'end meeting' to finish."
    )

def finish_session():
    global meeting_id
    global wake_mode
    global command_mode

    flush_transcription_buffer()

    if meeting_id is not None:
        end_meeting(meeting_id)
        meeting_id = None

    led.off()

    command_mode = False
    wake_mode = True

    print_status("Meeting ended.")
    print_status("LED OFF")
    print_status("Waiting for wake word: Hey Pi")

# ============================================================
# TRANSCRIPTION PROCESSING
# ============================================================

def process_transcription(text):
    """
    Accumulate recognized utterances into approximately 30-second rows.

    Returns True when a stop phrase is detected.
    """

    global transcript_buffer_seconds

    text = text.strip().lower()

    if not text:
        return False

    print_status(f"Transcribed: {text}")

    if meeting_id is None:
        print_status(
            "Warning: no active meeting; transcription not saved."
        )
        return False

    transcript_buffer.append(text)
    stop_detected = contains_phrase(text, STOP_PHRASES)

    if transcript_buffer_seconds >= SQLITE_CHUNK_SECONDS or stop_detected:
        flush_transcription_buffer()

    return stop_detected


def flush_transcription_buffer():
    global transcript_buffer_seconds

    if not transcript_buffer:
        return

    if meeting_id is None:
        print_status("Warning: no active meeting; buffered text not saved.")
        transcript_buffer.clear()
        transcript_buffer_seconds = 0.0
        return

    combined_text = "\n".join(transcript_buffer)
    save_transcription(meeting_id, combined_text)
    transcript_buffer.clear()
    transcript_buffer_seconds = 0.0


# ============================================================
# CLEAN SHUTDOWN
# ============================================================

def stop_application(signum=None, frame=None):
    global running
    running = False


def cleanup():
    global meeting_id
    global mic_process

    print_status("Cleaning up...")

    try:
        if meeting_id is not None:
            flush_transcription_buffer()
            end_meeting(meeting_id)
            meeting_id = None
    except Exception as exc:
        print_status(f"Could not close meeting: {exc}")

    try:
        led.off()
        led.close()
    except Exception:
        pass

    if mic_process is not None:
        try:
            if mic_process.poll() is None:
                mic_process.terminate()

                try:
                    mic_process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    mic_process.kill()
                    mic_process.wait(timeout=2)
        except Exception as exc:
            print_status(f"Microphone cleanup error: {exc}")

    print_status("Application stopped.")


# ============================================================
# MAIN
# ============================================================

def main():
    global mic_process
    global transcript_buffer_seconds

    if not VOSK_MODEL_DIR.is_dir():
        raise FileNotFoundError(
            f"Vosk wake-word model directory not found: {VOSK_MODEL_DIR}"
        )

    missing_models = [path for path in MODEL_FILES if not path.is_file()]
    if missing_models:
        raise FileNotFoundError(
            "Sherpa-ONNX Whisper model files not found: "
            + ", ".join(str(path) for path in missing_models)
        )

    print_status("Initializing SQLite database...")
    init_db()

    print_status("Loading Vosk wake-word model...")
    wake_model = Model(str(VOSK_MODEL_DIR))
    wake_recognizer = create_wake_recognizer(wake_model)

    print_status("Loading Sherpa-ONNX Whisper model...")
    recognizer = create_recognizer()

    mic_process = start_microphone()

    print_status("Vosk wake-word model and Sherpa-ONNX transcription model loaded.")
    print_status("Bluetooth microphone started.")
    print_status("Waiting for wake word: Hey Pi")

    audio_buffer = []
    pre_roll = deque(maxlen=2)
    silence_chunks = 0
    recording_utterance = False

    while running:

        if mic_process.poll() is not None:
            raise RuntimeError(
                "The microphone process stopped unexpectedly."
            )

        audio_stream = mic_process.stdout

        if audio_stream is None:
            raise RuntimeError(
                "Could not read microphone audio."
            )

        data = audio_stream.read(AUDIO_CHUNK_SIZE)

        if not data:
            if not running:
                break

            time.sleep(0.02)
            continue

        if wake_mode:
            if wake_recognizer.AcceptWaveform(data):
                result = json.loads(wake_recognizer.Result())
                recognized_text = result.get("text", "").strip().lower()

                if recognized_text:
                    print_status(f"Wake recognition: {recognized_text}")
                    if contains_phrase(recognized_text, WAKE_WORDS):
                        begin_session()
                        wake_recognizer = create_wake_recognizer(wake_model)
                    else:
                        print_status("Wake phrase not matched; still waiting.")
            continue

        pcm_samples = np.frombuffer(data, dtype="<i2").astype(np.int32)
        peak = np.max(np.abs(pcm_samples)) / 32768.0

        if not recording_utterance:
            pre_roll.append(data)
            if peak >= SPEECH_THRESHOLD:
                audio_buffer = list(pre_roll)
                recording_utterance = True
                silence_chunks = 0
        else:
            audio_buffer.append(data)
            if peak < SPEECH_THRESHOLD:
                silence_chunks += 1
            else:
                silence_chunks = 0

            if (
                silence_chunks >= SILENCE_CHUNKS
                or len(audio_buffer) >= MAX_UTTERANCE_CHUNKS
            ):
                audio_bytes = b"".join(audio_buffer)
                utterance_duration = len(audio_bytes) / (
                    SAMPLE_RATE * CHANNELS * 2
                )
                recognized_text = transcribe_audio(
                    recognizer,
                    audio_bytes,
                )

                if recognized_text:
                    if command_mode:
                        transcript_buffer_seconds += utterance_duration
                        if process_transcription(recognized_text):
                            finish_session()

                audio_buffer = []
                pre_roll.clear()
                silence_chunks = 0
                recording_utterance = False


if __name__ == "__main__":

    signal.signal(signal.SIGINT, stop_application)
    signal.signal(signal.SIGTERM, stop_application)

    try:
        main()

    except KeyboardInterrupt:
        pass

    except Exception as exc:
        print_status(f"ERROR: {exc}")
        sys.exit_code = 1

    finally:
        cleanup()
