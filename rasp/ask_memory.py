
import os
from datetime import datetime
from pathlib import Path

import requests

from audio_recorder import record_audio
from offline_stt_func import transcribe_wav
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
RECORD_SECONDS = int(os.environ.get("SATHI_QUESTION_RECORD_SECONDS", "10"))
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


def main():
    print("Sathi voice Q&A. Press Ctrl+C to stop.")
    print(f"API: {QNA_API_URL}")
    if MEETING_ID:
        print(f"Meeting scope: {MEETING_ID}")
    else:
        print("Meeting scope: all available meetings")

    while True:
        try:
            record_audio(
                output_file=QUESTION_AUDIO_PATH,
                duration=RECORD_SECONDS,
            )
            question = transcribe_wav(QUESTION_AUDIO_PATH).strip()
        except KeyboardInterrupt:
            print("\nGoodbye!")
            break
        except Exception as error:
            print(f"Question recording/transcription error: {error}")
            continue

        if not question:
            print("No question recognized. Try again.")
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


if __name__ == "__main__":
    main()
