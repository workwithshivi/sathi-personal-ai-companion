# SĀTHI Transcript Memory Server

Spring Boot application for storing meeting transcripts as vector-searchable chunks and answering questions from the saved evidence. The demo uses the laptop-side Spring AI `ChatClient` flow.

## Architecture

```text
Transcript → POST /ai/transcripts → 350-token chunks → Ollama embeddings
                                                    ↓
                                      PostgreSQL + pgvector
                                                    ↑
Question → POST /ai/qna → scoped vector retrieval → Ollama ChatClient → answer + sources
```

`meeting_id` metadata scopes retrieval to a meeting. If omitted in a Q&A request, retrieval searches all transcripts uploaded through `/ai/transcripts`.

## Requirements

- Java 25
- PostgreSQL with pgvector, reachable at `localhost:5433`, database `sathi`
- Ollama at `http://localhost:11434/` with `nomic-embed-text` and `gemma:2b`
- Database credentials set as `DB_USER_NAME` and `DB_PASSWORD`

For example:

```bash
ollama pull nomic-embed-text
ollama pull gemma:2b
```

## Run

```bash
export DB_USER_NAME=your_database_user
export DB_PASSWORD=your_database_password
./mvnw spring-boot:run
```

The server listens on port `8080` and binds to `0.0.0.0` so a Raspberry Pi on the trusted LAN can reach it. Use the laptop's LAN IP from another device.

## API

### Save transcript

`POST /ai/transcripts`

Content-Type: `application/json`

```json
{
  "text": [
    "The team agreed to test the offline demo on Friday.",
    "Asha will measure response time."
  ],
  "device": "raspberry-pi",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

`text` is an array of transcript strings. The server combines the segments in order, splits the transcript into approximately 350-token chunks, creates embeddings, and stores them in pgvector. Ingestion does not call the chat model.

`meeting_id` is optional. The Pi should provide and reuse one ID for every request from the same meeting. If omitted, the server generates a UTC timestamp-based ID with a short unique suffix. The response is `201 Created`:

```json
{
  "status": "saved",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

### Ask a question

`POST /ai/qna`

Content-Type: `application/json`

```json
{
  "question": "What needs to be ready for the demo?",
  "meeting_id": "pi-meeting-20261008-morning"
}
```

`meeting_id` is optional. When supplied, retrieval is limited to that meeting; otherwise, it searches all transcripts saved through `/ai/transcripts`. The application retrieves relevant chunks, then uses the local Ollama model through Spring AI `ChatClient` to draft an answer grounded in those chunks. The response includes the answer and source metadata:

```json
{
  "answer": "Asha will measure response time, and the team plans to test the offline demo on Friday.",
  "meeting_id": "pi-meeting-20261008-morning",
  "sources": [
    {
      "meeting_id": "pi-meeting-20261008-morning",
      "chunk_index": 0,
      "parent_document_id": "f65f60c2-cbdb-46fc-ab5c-82e817861880",
      "score": 0.71
    }
  ]
}
```

The model is instructed to answer from retrieved evidence and avoid inventing missing details. Response-time reductions are calculated from retrieved baseline and percentage values when possible. If no relevant chunks pass the retrieval thresholds, the service returns a not-found answer.

## Postman smoke test

1. `POST http://<laptop-ip>:8080/ai/transcripts` with the transcript body above; copy the returned `meeting_id`.
2. `POST http://<laptop-ip>:8080/ai/qna` with several questions and that `meeting_id`.
3. Check the answer and `sources`; try paraphrases and unrelated questions.
4. Omit `meeting_id` to search all ChatClient-pipeline transcripts.

## Console logs

At INFO, the application logs request details, full transcript text, stored chunks, exact questions and answers, retrieved evidence chunks, and timings. These logs may contain sensitive meeting content; use sample data during the demo and protect the console output.

## Tests

```bash
./mvnw test
```
