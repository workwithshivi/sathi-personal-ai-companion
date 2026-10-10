
import os

import requests

from tts_engine import speak

QNA_API_URL = os.environ.get(
    "SATHI_QNA_URL",
    "http://10.153.210.18:8080/ai/qna",
)
MEETING_ID = os.environ.get("SATHI_MEETING_ID")
REQUEST_TIMEOUT_SECONDS = 120


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
    print("Sathi memory Q&A. Type 'exit' or 'quit' to stop.")
    print(f"API: {QNA_API_URL}")
    if MEETING_ID:
        print(f"Meeting scope: {MEETING_ID}")
    else:
        print("Meeting scope: all available meetings")

    while True:
        question = input("\nYou: ").strip()

        if question.lower() in {"exit", "quit"}:
            print("Goodbye!")
            break

        if not question:
            continue

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
