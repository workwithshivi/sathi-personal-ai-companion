# SĀTHI Transcript Memory Server

Spring Boot application for storing transcripts as vector-searchable chunks and answering questions about any subject covered by the saved evidence. The demo uses the laptop-side Spring AI `ChatClient` flow.

## Architecture

```text
Transcript → POST /ai/transcripts → 350-token chunks → Ollama embeddings
                                                    ↓
                                      PostgreSQL + pgvector
                                                    ↑
Question → POST /ai/qna → scoped vector retrieval → Ollama ChatClient → answer + sources
```

`meeting_id` metadata scopes retrieval to a meeting. If omitted in a Q&A request, retrieval searches all transcripts uploaded through `/ai/transcripts`.

## Round-two demo

For the offline demo sequence, prepared Postman requests, preflight checklist, architecture explanation, and a concise presentation script, see [ROUND_TWO_DEMO.md](ROUND_TWO_DEMO.md). It describes the current laptop-side implementation and separates live, implemented behavior from future enhancements.

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

During local development, Spring Boot DevTools watches the compiled classpath and restarts the application after code changes. Restart the application once after adding DevTools; subsequent edits reload automatically when the IDE or Maven recompiles the changed files. DevTools is optional and is not included as a dependency of applications that consume this project.

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

The model is instructed to answer from retrieved evidence and avoid inventing missing details. Percentage changes are calculated from matching baseline and change values when both are present in the same evidence chunk. If no relevant chunks pass the retrieval thresholds, the service returns a not-found answer.

## Q&A reliability

- The primary similarity threshold remains `0.55`; the lower fallback remains `0.45`.
- If primary retrieval yields fewer than two distinct chunks, the service retries at `0.45` and merges both result sets before deduplication. If evidence remains sparse, it retries at `0.20`; unscoped results are then restricted to matches close to the best score.
- Action and decision questions return matching transcript sentences directly. Yes/no questions require a directly matching sentence; relationship questions require an explicit sentence naming the requested subject and relationship. These paths abstain when the evidence does not directly support an answer.
- ChatClient generation uses a typed structured response. Every exact quote must appear in retrieved evidence, address the question, and retain any explicit date or numeric constraint in the question. Returned source metadata is limited to chunks containing the supporting sentences or quotes. These checks reduce unsupported answers, but they are not a complete semantic entailment proof.
- Retrieval recognizes broad question shapes such as definitions, actions, decisions, plans, and subject-specific relationships. These routes do not assume a particular business domain, product, incident type, or subject vocabulary.
- Repeated uploads of the same transcript to the same meeting receive deterministic chunk IDs. pgvector upserts by ID, so a network retry does not create another copy of those chunks. Uploading changed text still creates new chunk IDs; the service does not delete older meeting content.
- The generation temperature is `0.1` for more repeatable answers. The similarity thresholds and 350-token chunking are unchanged.
- INFO logs include request sizes, retrieval counts/scores, source metadata, stage timings, and errors. Transcript text, complete retrieved chunks, and exact question/answer content are off by default. Set `SATHI_LOG_CONTENT=true` when running locally with non-sensitive sample data to enable those detailed logs.
- The controller and Q&A service emit focused DEBUG logs for query planning, per-variant vector search counts/scores/latency, evidence selection, model support flags, quote validation, and source metadata. Exact query variants, transcript chunks, and generated answers remain gated by `SATHI_LOG_CONTENT=true`.
- Spring AI and application metrics are available at `GET /actuator/metrics`; liveness/readiness and general health are available at `GET /actuator/health`.

Spring AI's Ollama integration supports provider structured output, but schema behavior can vary by Ollama and model version. Confirm the configured `gemma:2b` Ollama version with the live service before the demo. See [Spring AI structured output](https://docs.spring.io/spring-ai/reference/api/structured-output.html) and [Ollama chat structured output](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html).

### Manual evaluation set

Run these against one known meeting first, then repeat without `meeting_id` to test all-meeting retrieval. For answerable questions, verify both the answer and at least one relevant source. For unanswerable questions, verify the service abstains instead of guessing.

| Case | Question | Expected behavior |
| --- | --- | --- |
| Direct fact | What quantity did the transcript record? | Return the recorded value and a source. |
| Paraphrase | Ask for that same fact using different wording. | Return the same fact despite the paraphrase. |
| Calculation | What is the resulting inventory level after the planned percentage increase? | Calculate only from matching baseline and percentage values in the same evidence chunk. |
| Action intent | What should we do next? | Retrieve assigned actions or next steps, with sources. |
| Decision intent | Which option did the group select? | Return the documented choice, with sources. |
| Subject-specific relationship | Did the supplier delay affect shipping? | Preserve explicit evidence; do not infer from silence. |
| Unsupported subject | Did the supplier delay affect gardening? | Abstain unless the transcript addresses that subject. |
| Missing detail | What value was recorded for an absent measurement? | Abstain if the transcript does not provide it. |
| Meeting isolation | Ask a fact from meeting A while requesting meeting B's ID. | Do not return meeting A's evidence. |
| Duplicate ingestion | Submit the identical transcript body twice with the same meeting ID. | Q&A should not show duplicated evidence chunks. |

Record for each run: question, expected answer facts, actual answer, source IDs, best similarity score, retrieval latency, generation latency, and whether abstention was correct. This makes retrieval failures distinguishable from answer-generation failures.

### pgvector tuning

The application keeps HNSW and cosine distance as configured. Before changing index settings, benchmark a representative question set against the same stored vectors and database. Compare answer-relevant recall, latency, and query plans; HNSW is approximate, and metadata filters can affect how many matching rows are examined. Record the pgvector extension version before testing iterative scans, since available HNSW options depend on that version. See the [pgvector index and filtering guidance](https://github.com/pgvector/pgvector/blob/master/README.md) and [Spring AI vector-store search options](https://docs.spring.io/spring-ai/reference/api/vectordbs.html).

## Postman smoke test

1. `POST http://<laptop-ip>:8080/ai/transcripts` with the transcript body above; copy the returned `meeting_id`.
2. `POST http://<laptop-ip>:8080/ai/qna` with several questions and that `meeting_id`.
3. Check the answer and `sources`; try paraphrases and unrelated questions.
4. Omit `meeting_id` to search all ChatClient-pipeline transcripts.

## Console logs

At INFO, the application logs request sizes, counts, source metadata, retrieval scores, stage timings, and errors. Detailed content logging can expose sensitive meeting data and is disabled by default. Enable it only for local debugging with sample transcripts using `SATHI_LOG_CONTENT=true`.

## Tests

```bash
./mvnw test
```
