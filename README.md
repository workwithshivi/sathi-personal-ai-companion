# SĀTHI Transcript Memory Server

Spring Boot service for receiving meeting transcripts, embedding and storing them in PostgreSQL with pgvector, and retrieving relevant transcript context for questions. The Raspberry Pi remains responsible for final reasoning and response generation.

## Current flow

```text
Raspberry Pi transcript
        ↓ POST /transcripts
Spring Boot → Ollama embeddings → PostgreSQL / pgvector
        ↑ POST /qna
Raspberry Pi receives relevant transcript passages and generates the answer locally
```

Transcripts are split into 350-token chunks. Retrieval uses a similarity threshold of `0.55`. Common action-item, decision, and future-plan phrasings are routed through simple query expansion. For those questions, the service searches both the original and expanded wording, merges the matches, removes duplicate passages, and keeps the highest-scoring copies. The service currently returns transcript evidence; it does not generate the final natural-language answer.

## Requirements

- Java 25
- PostgreSQL with the pgvector extension, reachable at `localhost:5433`, with a database named `sathi`
- Ollama reachable at `http://localhost:11434/` with the `nomic-embed-text` embedding model available
- Database credentials in `DB_USER_NAME` and `DB_PASSWORD`

For example, install the embedding model with:

```bash
ollama pull nomic-embed-text
```

The project configuration names `gemma:2b` as a chat model, but this server's current Q&A endpoint performs retrieval only. Final answer generation is intended to run on the Raspberry Pi.

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
  "device": "raspberry-pi"
}
```

Successful response (`201 Created`):

```json
{
  "status": "saved",
  "meeting_id": "meeting-7e92a46b-5d21-4f3e-8e25-df246bfe2719"
}
```

The server creates the meeting ID. The Pi/app should retain it for questions about this meeting.

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

## Testing with Postman

1. Send the transcript to `POST http://<laptop-ip>:8080/transcripts` and copy `meeting_id` from the response.
2. Send different questions to `POST http://<laptop-ip>:8080/qna`, including the copied `meeting_id`.
3. Check `intent`, `sources`, and the passage text in `answer`. Try paraphrases such as “What are my action items?”, “What is the to-do?”, and “What can we do next?”.
4. Omit `meeting_id` to test searching across meetings. Use unrelated questions to check the no-relevant-memory response.

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
