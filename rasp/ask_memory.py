
from database import init_db, get_all_transcriptions
from llm import ask_gemma
from tts_engine import speak


init_db()

print("Loading memories...")

memories = get_all_transcriptions()

print(f"Found {len(memories)} memories.")

while True:
    question = input("\nYou: ").strip()

    if question.lower() in ["exit", "quit"]:
        print("Goodbye!")
        break

    if not question:
        continue

    answer = ask_gemma(
        question,
        memories
    )

    answer = str(answer).strip()

    print()
    print("Gemma:", answer)

    if answer:
        try:
            speak(answer)
        except Exception as error:
            print(f"TTS error: {error}")
