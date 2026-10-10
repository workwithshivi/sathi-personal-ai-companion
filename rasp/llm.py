import requests

OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL = "gemma3:1b"


def ask_gemma(question, memories):

    context = ""

    for timestamp, text in memories:
        context += f"[{timestamp}] {text}\n"

    prompt = f"""
You are a personal voice assistant running on a Raspberry Pi.

You have access to the user's previous voice transcriptions.

Use the memories below to answer the user's question.

MEMORIES:
{context}

USER QUESTION:
{question}

Instructions:
- Answer clearly and briefly.
- Use the memories when they contain the answer.
- Do not invent personal information.
- If the answer is not present in the memories, say:
  "I don't have that information in my memory."

ANSWER:
"""

    response = requests.post(
        OLLAMA_URL,
        json={
            "model": MODEL,
            "prompt": prompt,
            "stream": False
        },
        timeout=120
    )

    response.raise_for_status()

    data = response.json()

    return data["response"].strip()
