# SĀTHI Transcript Memory Server

Spring Boot service for receiving meeting transcripts, embedding and storing them in PostgreSQL with pgvector, and retrieving relevant transcript context. It supports both the original Raspberry Pi answer-generation flow and an optional laptop-side Spring AI ChatClient flow.

## Current flow

```text
Original retrieval flow:
Raspberry Pi transcript
        ↓ POST /transcripts
Spring Boot → Ollama embeddings → PostgreSQL / pgvector
        ↑ POST /qna
Raspberry Pi receives relevant transcript passages and generates the answer locally

Optional ChatClient flow:
Transcript → POST /ai/transcripts → Ollama embeddings → PostgreSQL / pgvector
Question  → POST /ai/qna        → scoped vector retrieval → Ollama ChatClient answer
```

Transcripts are split into 350-token chunks. Retrieval uses a similarity threshold of `0.55`. Common action-item, decision, and future-plan phrasings are routed through simple query expansion. For those questions, the service searches both the original and expanded wording, merges the matches, removes duplicate passages, and keeps the highest-scoring copies. The original `/qna` endpoint returns transcript evidence; `/ai/qna` generates the natural-language answer on the laptop.

## Requirements

- Java 25
- PostgreSQL with the pgvector extension, reachable at `localhost:5433`, with a database named `sathi`
- Ollama reachable at `http://localhost:11434/` with the `nomic-embed-text` embedding model available
- Database credentials in `DB_USER_NAME` and `DB_PASSWORD`

For example, install the embedding model with:

```bash
ollama pull nomic-embed-text
ollama pull gemma:2b
```

`gemma:2b` is needed for `/ai/qna`; the original retrieval-only endpoints only need the embedding model.

The original `/qna` endpoint returns retrieved passages for answer generation on the Pi. The optional `/ai/qna` endpoint uses the configured local Ollama chat model (`gemma:2b`) through Spring AI `ChatClient` to draft the answer on the laptop.

## Configure and run

Set the database credentials in your shell or IDE run configuration. Then run the server:

```bash
export DB_USER_NAME=your_database_user
export DB_PASSWORD=your_database_password
./mvnw spring-boot:run
```

The server listens on port `8080` and binds to `0.0.0.0` so another device on the LAN can reach it. From the Raspberry Pi, replace `localhost` with the laptop's LAN IP address.

## API

### Save a transcript

`POST /transcripts`

Set `Content-Type: application/json`.

```json
{
  "text": "Shivam will test transcript ingestion. The team will verify the mute switch.",
  "device": "raspberry-pi",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

Successful response (`201 Created`):

```json
{
  "status": "saved",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

`meeting_id` is optional on ingestion. The Raspberry Pi should send the same ID with every batch from one meeting; SĀTHI preserves that ID. IDs must be 1–128 characters using letters, digits, dots, underscores, colons, or hyphens. If omitted, SĀTHI generates an ID in UTC timestamp format with a short unique suffix, for example `meeting-20261008T153012Z-a7f3c901`. This makes server-generated IDs easier to identify by meeting time while reducing collision risk. Existing UUID-style IDs remain valid. Use the returned ID in Q&A requests to search only that meeting.

### Retrieve context for a question

`POST /qna`

Set `Content-Type: application/json`.

Pass the meeting ID to search only that meeting:

```json
{
  "question": "What can we do next?",
  "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719"
}
```

The `meeting_id` field is optional. When omitted, retrieval searches across stored meetings. A successful response includes the retrieved context, detected intent, and source metadata:

```json
{
  "answer": "[meeting_id=meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719, chunk_index=1, parent_document_id=f65f60c2-cbdb-46fc-ab5c-82e817861880]\nShivam will test transcript ingestion...",
  "intent": "ACTION_ITEM",
  "sources": [
    {
      "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719",
      "chunk_index": 1,
      "parent_document_id": "f65f60c2-cbdb-46fc-ab5c-82e817861880",
      "score": 0.71
    }
  ]
}
```

The `answer` field contains retrieved transcript passages, not a generated summary. The Pi can use those passages as evidence for its local answer.

### ChatClient transcript ingestion

`POST /ai/transcripts`

This optional endpoint stores the original transcript in the same pgvector store, using the shared 350-token splitter. It returns a `meeting_id` like `/transcripts`. Ingestion does not invoke the chat model, keeping upload latency lower. The pipeline metadata keeps `/ai/qna` retrieval scoped to transcripts uploaded through this endpoint.

```json
{
  "text": "The team agreed to test the offline demo on Friday. Asha will measure response time.",
  "device": "laptop",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

The `meeting_id` field is optional here too, and follows the same Pi-supplied or server-generated behavior as `/transcripts`.

### ChatClient question answering

`POST /ai/qna`

```json
{
  "question": "What needs to be ready for the demo?",
  "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719"
}
```

`meeting_id` is optional. When supplied, retrieval is limited to that meeting; otherwise it searches transcripts saved through `/ai/transcripts`. The service sends the question and up to five relevant chunks to the local ChatClient. Answer generation uses a low temperature and a response-token cap to favor concise, stable answers. The response contains the generated answer and source metadata:

```json
{
  "answer": "Asha will measure response time, and the team plans to test the offline demo on Friday.",
  "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719",
  "sources": [
    {
      "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719",
      "chunk_index": 0,
      "parent_document_id": "f65f60c2-cbdb-46fc-ab5c-82e817861880",
      "score": 0.71
    }
  ]
}
```

The model is instructed to answer from retrieved transcript evidence and avoid inventing missing details. Percentage reductions for response-time questions are calculated deterministically when both the baseline and reduction appear in the same retrieved chunk; other questions use the ChatClient. If no relevant chunks pass the retrieval threshold, the model is not called and the service returns a not-found answer.

## Testing with Postman

1. Send the transcript to `POST http://<laptop-ip>:8080/transcripts` and copy `meeting_id` from the response.
2. Send different questions to `POST http://<laptop-ip>:8080/qna`, including the copied `meeting_id`.
3. Check `intent`, `sources`, and the passage text in `answer`. Try paraphrases such as “What are my action items?”, “What is the to-do?”, and “What can we do next?”.
4. Omit `meeting_id` to test searching across meetings. Use unrelated questions to check the no-relevant-memory response.
5. Compare the original Pi flow with `POST /ai/transcripts` and `POST /ai/qna` using the same transcript and questions. The ChatClient endpoints run answer generation on the laptop.

## Console logging

At the default `INFO` level, the console shows intent, meeting scope, threshold, top-k, elapsed time, and each result's meeting ID, chunk index, score, and parent document ID. Transcript text is not logged at this level.

To include the question and short previews of retrieved passages during local testing, set:

```bash
SATHI_RETRIEVAL_LOG_LEVEL=DEBUG ./mvnw spring-boot:run
```

Debug previews contain transcript text. Enable this only when needed for local testing.

## Tests

Run the unit test suite with:

```bash
./mvnw test
```
