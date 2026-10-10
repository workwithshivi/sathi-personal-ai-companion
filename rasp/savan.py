
import json
import os
import signal
import subprocess
import sys
import time

from gpiozero import LED
from vosk import Model, KaldiRecognizer

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

MODEL_PATH = (
    "/home/piuser/sathi-personal-ai-companion/rasp"
    "vosk-model-small-en-us-0.15"
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
# VOSK RECOGNIZERS
# ============================================================

def create_wake_recognizer(model):
    """
    Restricted vocabulary is appropriate for wake-word mode.
    """

    grammar = json.dumps(
        WAKE_WORDS + ["[unk]"]
    )

    return KaldiRecognizer(
        model,
        SAMPLE_RATE,
        grammar,
    )


def create_speech_recognizer(model):
    """
    No grammar is supplied, so Vosk can recognize general speech.
    """

    recognizer = KaldiRecognizer(
        model,
        SAMPLE_RATE,
    )

    # Include word-level details in the result if needed later.
    recognizer.SetWords(True)

    return recognizer


# ============================================================
# SESSION MANAGEMENT
# ============================================================

def begin_session(model):
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

    return create_speech_recognizer(model)


def finish_session(model):
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

    return create_wake_recognizer(model)


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
    global wake_mode
    global command_mode

    if not os.path.isdir(MODEL_PATH):
        raise FileNotFoundError(
            f"Vosk model directory not found: {MODEL_PATH}"
        )

    print_status("Initializing SQLite database...")
    init_db()

    print_status("Loading Vosk model...")
    model = Model(MODEL_PATH)

    wake_recognizer = create_wake_recognizer(model)
    speech_recognizer = None

    mic_process = start_microphone()

    print_status("Vosk model loaded.")
    print_status("Bluetooth microphone started.")
    print_status("Waiting for wake word: Hey Pi")

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

        # ----------------------------------------------------
        # WAKE WORD MODE
        # ----------------------------------------------------

        if wake_mode:

            if wake_recognizer.AcceptWaveform(data):
                result = json.loads(
                    wake_recognizer.Result()
                )

                recognized_text = (
                    result.get("text", "")
                    .strip()
                    .lower()
                )

                if not recognized_text:
                    continue

                print_status(
                    f"Wake recognition: {recognized_text}"
                )

                wake_detected = any(
                    word in recognized_text
                    for word in WAKE_WORDS
                )

                if wake_detected:
                    speech_recognizer = begin_session(model)

                    # Prevent any residual wake-word audio from
                    # being used as the first speech utterance.
                    wake_recognizer = create_wake_recognizer(model)

            continue

        # ----------------------------------------------------
        # ACTIVE MEETING MODE
        # ----------------------------------------------------

        if command_mode and speech_recognizer is not None:

            if speech_recognizer.AcceptWaveform(data):
                result = json.loads(
                    speech_recognizer.Result()
                )

                recognized_text = (
                    result.get("text", "")
                    .strip()
                    .lower()
                )

                if not recognized_text:
                    continue

                stop_detected = process_transcription(
                    recognized_text
                )

                if stop_detected:
                    wake_recognizer = finish_session(model)
                    speech_recognizer = None

            # Do not use a silence timeout.
            # Continue listening until a stop phrase is recognized.


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
