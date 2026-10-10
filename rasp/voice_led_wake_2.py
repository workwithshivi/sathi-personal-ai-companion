import subprocess
import json
import time
import os

import numpy as np
from vosk import Model, KaldiRecognizer
from gpiozero import LED

from database import init_db, save_transcription

# ============================================================
# CONFIGURATION
# ============================================================

# LED connected to GPIO 17
GPIO_PIN = 17

# Bluetooth microphone
MIC_TARGET = "90"

# Vosk model
MODEL_PATH = "/home/piuser/sathi-personal-ai-companion/rasp/vosk-model-small-en-us-0.15"

# Audio
SAMPLE_RATE = 16000
CHANNELS = 1

# Wake words
WAKE_WORDS = [
    "hey savan",
    "hey saavan",
    "hey seven",
    "hey pi",
    "hey pie",
    "hey p"
]

# Stop command listening after this much silence
SILENCE_TIMEOUT = 5.0

# Microphone volume threshold
SILENCE_THRESHOLD = 500

# All recognized speech is appended here
# TRANSCRIPTION_FILE = "/home/piuser/sathi-personal-ai-companion/rasp/transcriptions.txt"

init_db()

# ============================================================
# LED
# ============================================================

led = LED(GPIO_PIN)


# ============================================================
# CALCULATE SOUND LEVEL
# ============================================================

def calculate_rms(data):

    if not data:
        return 0

    audio = np.frombuffer(
        data,
        dtype=np.int16
    )

    if len(audio) == 0:
        return 0

    rms = np.sqrt(
        np.mean(
            audio.astype(np.float64) ** 2
        )
    )

    return rms


# ============================================================
# START BLUETOOTH MICROPHONE
# ============================================================

def start_microphone():

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
        "-"
    ]

    print("Starting Bluetooth microphone...")

    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL
    )

    return process


# ============================================================
# LOAD MODEL
# ============================================================

print("Loading Vosk model...")

if not os.path.exists(MODEL_PATH):

    print(
        f"ERROR: Model not found at {MODEL_PATH}"
    )

    exit(1)


model = Model(MODEL_PATH)

print("Vosk model loaded.")


# ============================================================
# START MICROPHONE
# ============================================================

mic_process = start_microphone()

print("Bluetooth microphone started.")
print()
print("======================================")
print("Waiting for wake word: 'Hey Pi'")
print("======================================")
print()


# ============================================================
# WAKE WORD RECOGNIZER
# ============================================================

wake_grammar = json.dumps(
    WAKE_WORDS + ["[unk]"]
)

wake_recognizer = KaldiRecognizer(
    model,
    SAMPLE_RATE,
    wake_grammar
)


# ============================================================
# STATE
# ============================================================

wake_mode = True
command_mode = False

last_sound_time = time.time()


# ============================================================
# MAIN LOOP
# ============================================================

try:

    while True:

        data = mic_process.stdout.read(4000)

        if not data:

            print("Microphone stopped.")
            break


        # ====================================================
        # WAKE WORD MODE
        # ====================================================

        if wake_mode:

            if wake_recognizer.AcceptWaveform(data):

                result = json.loads(
                    wake_recognizer.Result()
                )

                text = result.get(
                    "text",
                    ""
                ).lower().strip()


                if text:

                    print(
                        f"Wake recognition: {text}"
                    )


                    # Check whether wake word exists
                    wake_detected = False

                    for wake_word in WAKE_WORDS:

                        if wake_word in text:

                            wake_detected = True
                            break


                    if wake_detected:

                        # Save wake word
                        save_transcription(
                            text
                        )


                        # ====================================
                        # WAKE WORD DETECTED
                        # ====================================

                        print()
                        print("======================================")
                        print("WAKE WORD DETECTED")
                        print("LED ON")
                        print("Listening...")
                        print("======================================")
                        print()


                        # Turn LED ON
                        led.on()


                        # Change state
                        wake_mode = False
                        command_mode = True


                        # Reset timer
                        last_sound_time = time.time()


                        # IMPORTANT:
                        # Normal recognizer WITHOUT grammar.
                        #
                        # This means it can recognize
                        # ANY English speech.
                        #
                        command_recognizer = KaldiRecognizer(
                            model,
                            SAMPLE_RATE
                        )


            continue


        # ====================================================
        # NORMAL SPEECH / TRANSCRIPTION MODE
        # ====================================================

        if command_mode:

            # -----------------------------------------------
            # Detect sound
            # -----------------------------------------------

            volume = calculate_rms(data)

            if volume > SILENCE_THRESHOLD:

                last_sound_time = time.time()


            # -----------------------------------------------
            # TRANSCRIBE ANY SPEECH
            # -----------------------------------------------

            if command_recognizer.AcceptWaveform(data):

                result = json.loads(
                    command_recognizer.Result()
                )

                text = result.get(
                    "text",
                    ""
                ).strip()


                if text:

                    print()
                    print(
                        f"Transcribed: {text}"
                    )

                    # Save ANY words
                    save_transcription(
                        text
                    )

                    print()


            # -----------------------------------------------
            # 5 SECOND SILENCE
            # -----------------------------------------------

            if (
                time.time() - last_sound_time
                >= SILENCE_TIMEOUT
            ):

                print()
                print(
                    "No sound detected for 5 seconds."
                )

                print(
                    "Stopping speech listening..."
                )


                # Turn LED OFF
                led.off()


                # Return to wake mode
                command_mode = False
                wake_mode = True


                # Create fresh wake recognizer
                wake_recognizer = KaldiRecognizer(
                    model,
                    SAMPLE_RATE,
                    wake_grammar
                )


                print()
                print("LED OFF")
                print(
                    "Waiting for 'Hey Pi'..."
                )
                print()


# ============================================================
# CLEANUP
# ============================================================

except KeyboardInterrupt:

    print()
    print("Stopping program...")


finally:

    led.off()


    if mic_process:

        mic_process.terminate()

        try:

            mic_process.wait(
                timeout=2
            )

        except subprocess.TimeoutExpired:

            mic_process.kill()


    print("LED OFF")
    print("Microphone stopped.")
    print("Program exited.")
