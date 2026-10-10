
import os
import subprocess
import time
import wave
from pathlib import Path

import requests

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

from gpiozero import Button, LED
from tts_engine import speak

QNA_API_URL = os.environ.get(
    "SATHI_QNA_URL",
    "http://10.153.210.18:8080/ai/qna",
)
REQUEST_TIMEOUT_SECONDS = 120
BUTTON_PIN = int(os.environ.get("SATHI_BUTTON_GPIO", "22"))
BUTTON_PULL_UP = os.environ.get("SATHI_BUTTON_PULL_UP", "true").lower() == "true"
LED_PIN = int(os.environ.get("SATHI_LED_GPIO", "23"))
LED_ACTIVE_HIGH = os.environ.get("SATHI_LED_ACTIVE_HIGH", "true").lower() == "true"
SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2
AUDIO_CHUNK_BYTES = 4000
QUESTION_AUDIO_PATH = (
    Path(__file__).resolve().parent / "recordings" / "memory_question.wav"
)


def ask_server(question):
    payload = {"question": question}

    response = requests.post(
        QNA_API_URL,
        json=payload,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return response.json()


def record_question_while_held(led):
    QUESTION_AUDIO_PATH.parent.mkdir(parents=True, exist_ok=True)
    audio_chunks = []

    with Button(
        BUTTON_PIN,
        pull_up=BUTTON_PULL_UP,
        bounce_time=0.1,
    ) as button:
        print("Waiting for button. Hold it while asking your question.", flush=True)
        while not button.is_pressed:
            time.sleep(0.02)

        led.on()
        print("Listening; release the button to submit.", flush=True)

        recorder = subprocess.Popen(
            [
                "arecord",
                "-q",
                "-t", "raw",
                "-f", "S16_LE",
                "-r", str(SAMPLE_RATE),
                "-c", str(CHANNELS),
                "-",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            bufsize=0,
        )

        try:
            if recorder.stdout is None:
                raise RuntimeError("Could not read microphone audio.")

            while button.is_pressed:
                chunk = recorder.stdout.read(AUDIO_CHUNK_BYTES)
                if not chunk:
                    raise RuntimeError("Microphone recording stopped unexpectedly.")
                audio_chunks.append(chunk)
            print("Button released; processing question.", flush=True)
        finally:
            if recorder.poll() is None:
                recorder.terminate()
                try:
                    recorder.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    recorder.kill()
                    recorder.wait(timeout=2)

            if recorder.stdout is not None:
                recorder.stdout.close()
            led.off()

    audio_data = b"".join(audio_chunks)
    if not audio_data:
        return ""

    with wave.open(str(QUESTION_AUDIO_PATH), "wb") as audio_file:
        audio_file.setnchannels(CHANNELS)
        audio_file.setsampwidth(SAMPLE_WIDTH_BYTES)
        audio_file.setframerate(SAMPLE_RATE)
        audio_file.writeframes(audio_data)

    from offline_stt_func import transcribe_wav

    return transcribe_wav(QUESTION_AUDIO_PATH).strip()


def main():
    led = LED(LED_PIN, active_high=LED_ACTIVE_HIGH)
    print("Sathi voice Q&A. Hold the button to record; release to submit.")
    print(f"API: {QNA_API_URL}")
    print(f"Button: BCM {BUTTON_PIN}; LED: BCM {LED_PIN}")

    try:
        while True:
            try:
                question = record_question_while_held(led)
            except KeyboardInterrupt:
                print("\nVoice Q&A stopped.", flush=True)
                break
            except Exception as error:
                led.off()
                print(f"Question recording/transcription error: {error}")
                continue

            if not question:
                print("No question recognized. Waiting for the next press.")
                continue

            print(f"\nYou: {question}")

            try:
                result = ask_server(question)
                answer = str(result.get("answer", "")).strip()

                if not answer:
                    print("Sathi: The API returned an empty answer.")
                    continue

                print("\nSathi:", answer)

                try:
                    speak(answer)
                except Exception as error:
                    print(f"TTS error: {error}")

            except (requests.RequestException, ValueError) as error:
                print(f"Q&A API error: {error}")

            print("Waiting for the next question.", flush=True)
    finally:
        led.off()
        led.close()


if __name__ == "__main__":
    main()
