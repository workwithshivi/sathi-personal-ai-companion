import subprocess
import json
import time
import math
import struct

from vosk import Model, KaldiRecognizer, SetLogLevel
from gpiozero import LED


# =========================
# CONFIGURATION
# =========================

MODEL_PATH = "vosk-model-small-en-us-0.15"

GPIO_PIN = 17

MIC_SOURCE = "90"

SAMPLE_RATE = 16000

CHANNELS = 1

SILENCE_TIMEOUT = 5

# Adjust this if your environment is noisy
SILENCE_THRESHOLD = 500


# =========================
# GPIO
# =========================

led = LED(GPIO_PIN)


# =========================
# VOSK
# =========================

SetLogLevel(-1)

print("Loading model...")

model = Model(MODEL_PATH)


# =========================
# MICROPHONE
# =========================

command = [
    "pw-record",
    "--target", MIC_SOURCE,
    "--format", "s16",
    "--rate", str(SAMPLE_RATE),
    "--channels", "1",
    "-"
]

mic = subprocess.Popen(
    command,
    stdout=subprocess.PIPE,
    stderr=subprocess.DEVNULL
)


# =========================
# AUDIO LEVEL
# =========================

def calculate_rms(data):

    if len(data) < 2:
        return 0

    samples = struct.unpack(
        "<{}h".format(len(data) // 2),
        data
    )

    square_sum = sum(
        sample * sample
        for sample in samples
    )

    return math.sqrt(
        square_sum / len(samples)
    )


# =========================
# WAKE WORD
# =========================

wake_recognizer = KaldiRecognizer(
    model,
    SAMPLE_RATE,
    json.dumps([
        "hey pi",
        "hey pie",
        "hello pi",
        "hey saavan",
        "hey savan",
        "hello savan",
        "[unk]"
    ])
)


# =========================
# MAIN LOOP
# =========================

print()
print("============================")
print(" Raspberry Pi Voice Control ")
print("============================")
print()
print("Waiting for wake word...")
print('Say: "Hey Pi"')
print()


listening_for_command = False
last_sound_time = 0


try:

    while True:

        data = mic.stdout.read(4000)

        if not data:
            print("Microphone stopped.")
            break


        # =====================================
        # MODE 1: WAIT FOR WAKE WORD
        # =====================================

        if not listening_for_command:

            if wake_recognizer.AcceptWaveform(data):

                result = json.loads(
                    wake_recognizer.Result()
                )

                text = result.get(
                    "text",
                    ""
                ).lower().strip()

                if text:

                    print("Heard:", text)

                if (
                    "hey pi" in text
                    or "hey pie" in text
                    or "hello pi" in text
                ):

                    print()
                    print("🔥 WAKE WORD DETECTED")
                    print("🎤 Listening for command...")

                    listening_for_command = True

                    last_sound_time = time.time()

                    # LED ON = listening
                    led.on()

                    # New recognizer for command
                    command_recognizer = KaldiRecognizer(
                        model,
                        SAMPLE_RATE,
                        json.dumps([
                            "turn on led",
                            "turn off led",
                            "turn on light",
                            "turn off light",
                            "led on",
                            "led off",
                            "light on",
                            "light off",
                            "[unk]"
                        ])
                    )

                    # Reset wake recognizer
                    wake_recognizer = KaldiRecognizer(
                        model,
                        SAMPLE_RATE,
                        json.dumps([
                            "hey pi",
                            "hey pie",
                            "hello pi",
                            "[unk]"
                        ])
                    )

                    continue


        # =====================================
        # MODE 2: LISTEN FOR COMMAND
        # =====================================

        else:

            # -----------------------------
            # Check sound level
            # -----------------------------

            rms = calculate_rms(data)

            if rms > SILENCE_THRESHOLD:

                last_sound_time = time.time()


            # -----------------------------
            # Speech recognition
            # -----------------------------

            if command_recognizer.AcceptWaveform(data):

                result = json.loads(
                    command_recognizer.Result()
                )

                text = result.get(
                    "text",
                    ""
                ).lower().strip()

                if text:

                    print("Command:", text)

                # -------------------------
                # TURN LED ON
                # -------------------------

                if (
                    "turn on led" in text
                    or "turn on light" in text
                    or text == "led on"
                    or text == "light on"
                ):

                    led.on()

                    print("💡 LED ON")

                    # Keep listening
                    last_sound_time = time.time()


                # -------------------------
                # TURN LED OFF
                # -------------------------

                elif (
                    "turn off led" in text
                    or "turn off light" in text
                    or text == "led off"
                    or text == "light off"
                ):

                    led.off()

                    print("💡 LED OFF")

                    last_sound_time = time.time()


            # =================================
            # 5 SECOND SILENCE
            # =================================

            if time.time() - last_sound_time >= SILENCE_TIMEOUT:

                print()
                print("⏱️ No sound for 5 seconds")
                print("🛑 Stopping command listening")

                # LED OFF
                led.off()

                listening_for_command = False

                print('Waiting for "Hey Pi"...')
                print()

                # Reset wake recognizer
                wake_recognizer = KaldiRecognizer(
                    model,
                    SAMPLE_RATE,
                    json.dumps([
                        "hey pi",
                        "hey pie",
                        "hello pi",
                        "[unk]"
                    ])
                )


except KeyboardInterrupt:

    print()
    print("Stopping...")


finally:

    led.off()

    mic.terminate()

    print("LED OFF")
    print("Voice controller stopped.")
