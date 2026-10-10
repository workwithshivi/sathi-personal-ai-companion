
import os
import subprocess
import time
import wave
from datetime import datetime
from pathlib import Path

import requests

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

from gpiozero import Button

from tts_engine import speak

QNA_API_URL = os.environ.get(
    "SATHI_QNA_URL",
    "http://10.153.210.18:8080/ai/qna",
)
MEETING_ID = os.environ.get(
    "SATHI_MEETING_ID",
    datetime.now().strftime("%Y%m%d"),
)
REQUEST_TIMEOUT_SECONDS = 120
BUTTON_PIN = int(os.environ.get("SATHI_BUTTON_GPIO", "22"))
BUTTON_PULL_UP = os.environ.get("SATHI_BUTTON_PULL_UP", "true").lower() == "true"
SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2
AUDIO_CHUNK_BYTES = 4000
QUESTION_AUDIO_PATH = (
    Path(__file__).resolve().parent / "recordings" / "memory_question.wav"
)


def ask_server(question):
    payload = {"question": question}
    if MEETING_ID:
        payload["meeting_id"] = MEETING_ID

    response = requests.post(
        QNA_API_URL,
        json=payload,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return response.json()


def record_question_until_button():
    QUESTION_AUDIO_PATH.parent.mkdir(parents=True, exist_ok=True)
    audio_chunks = []

    with Button(
        BUTTON_PIN,
        pull_up=BUTTON_PULL_UP,
        bounce_time=0.1,
    ) as button:
        while button.is_pressed:
            time.sleep(0.02)

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
            print("Speak your question. Press the button again when finished.")
            if recorder.stdout is None:
                raise RuntimeError("Could not read microphone audio.")

            while True:
                if button.is_pressed:
                    print("Button press detected; stopping question recording.")
                    break
                chunk = recorder.stdout.read(AUDIO_CHUNK_BYTES)
                if not chunk:
                    raise RuntimeError("Microphone recording stopped unexpectedly.")
                audio_chunks.append(chunk)
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
    print("Sathi voice Q&A. Press the button to stop recording.")
    print(f"API: {QNA_API_URL}")
    if MEETING_ID:
        print(f"Meeting scope: {MEETING_ID}")
    else:
        print("Meeting scope: all available meetings")

    try:
        question = record_question_until_button()
    except KeyboardInterrupt:
        print("\nQuestion cancelled.")
        return
    except Exception as error:
        print(f"Question recording/transcription error: {error}")
        return

    if not question:
        print("No question recognized.")
        return

    print(f"\nYou: {question}")

    try:
        result = ask_server(question)
        answer = str(result.get("answer", "")).strip()

        if not answer:
            print("Sathi: The API returned an empty answer.")
            return

        print("\nSathi:", answer)

        try:
            speak(answer)
        except Exception as error:
            print(f"TTS error: {error}")

    except (requests.RequestException, ValueError) as error:
        print(f"Q&A API error: {error}")


if __name__ == "__main__":
    main()
