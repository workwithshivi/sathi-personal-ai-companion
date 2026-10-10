
import signal
import subprocess
import sys
import time
from collections import deque
from pathlib import Path

from gpiozero import LED
import numpy as np
import sherpa_onnx

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

WAKE_WORDS = [
    "hey savan",
    "hey saavan",
    "hey seven",
    "hey pie",
]

STOP_PHRASES = [
    "stop listening",
    "end meeting",
    "finish meeting",
    "close session",
]

# PipeWire recording format:
# 16-bit signed PCM, 16 kHz, mono.
AUDIO_CHUNK_SIZE = 4000
SPEECH_THRESHOLD = 0.005
SILENCE_CHUNKS = 6
MAX_UTTERANCE_CHUNKS = 160


# ============================================================
# INITIALIZATION
# ============================================================

led = LED(GPIO_PIN)

meeting_id = None
wake_mode = True
command_mode = False

mic_process = None
running = True


def print_status(message):
    print(f"\n[{time.strftime('%H:%M:%S')}] {message}", flush=True)


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

def create_recognizer():
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(MODEL_DIR / "encoder.int8.onnx"),
        decoder=str(MODEL_DIR / "decoder.int8.onnx"),
        tokens=str(MODEL_DIR / "tokens.txt"),
        num_threads=2,
        decoding_method="greedy_search",
        language="hi",
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
    Save each finalized utterance in SQLite.
    If a stop phrase occurs, end the current meeting.

    Returns True when a stop phrase is detected.
    """

    global meeting_id

    text = text.strip().lower()

    if not text:
        return False

    print_status(f"Transcribed: {text}")

    if meeting_id is None:
        print_status(
            "Warning: no active meeting; transcription not saved."
        )
        return False

    # Save the utterance, including the stop phrase.
    save_transcription(meeting_id, text)

    # Stop phrases are matched as whole phrases.
    normalized_text = " ".join(text.split())

    stop_detected = any(
        phrase in normalized_text
        for phrase in STOP_PHRASES
    )

    return stop_detected


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

    missing_models = [path for path in MODEL_FILES if not path.is_file()]
    if missing_models:
        raise FileNotFoundError(
            "Sherpa-ONNX Whisper model files not found: "
            + ", ".join(str(path) for path in missing_models)
        )

    print_status("Initializing SQLite database...")
    init_db()

    print_status("Loading Sherpa-ONNX Whisper model...")
    recognizer = create_recognizer()

    mic_process = start_microphone()

    print_status("Sherpa-ONNX model loaded.")
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
                recognized_text = transcribe_audio(
                    recognizer,
                    b"".join(audio_buffer),
                )

                if recognized_text:
                    if wake_mode:
                        print_status(f"Wake recognition: {recognized_text}")
                        if any(phrase in recognized_text for phrase in WAKE_WORDS):
                            begin_session()
                    elif command_mode:
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
