from database import init_db, get_all_transcriptions
from llm import ask_gemma


init_db()

print("Loading memories...")

memories = get_all_transcriptions()

print(f"Found {len(memories)} memories.")

while True:

    question = input("\nYou: ").strip()

    if question.lower() in ["exit", "quit"]:

        break

    if not question:

        continue

    answer = ask_gemma(
        question,
        memories
    )

    print()
    print("Gemma:", answer)
