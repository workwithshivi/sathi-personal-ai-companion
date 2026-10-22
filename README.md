# SĀTHI Transcript Memory Server

Spring Boot application for storing transcripts as vector-searchable chunks and answering questions about any subject covered by the saved evidence. The demo uses the laptop-side Spring AI `ChatClient` flow.

## Architecture

```text
Transcript → POST /ai/transcripts → 350-token chunks + grounded meeting summary
                                        ↓                         ↓
                                  Ollama embeddings       PostgreSQL + pgvector
                                        ↑                         ↑
Question → POST /ai/qna → vector retrieval OR all-meeting summaries → ChatClient → answer + sources
```

`meeting_id` metadata scopes retrieval to a meeting. Without one, ordinary questions search across meetings. Questions requesting a collection-wide overview use one grounded summary per saved meeting, so top vector matches from a single meeting cannot silently stand in for the whole collection. Existing transcript records without summaries are summarized and cached when that overview path is first used.

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
  "meeting_id": "pi-meeting-20261008-morning",
  "conversation_id": "demo-session-1"
}
```

`meeting_id` and `conversation_id` are optional. When `meeting_id` is supplied, retrieval is limited to that meeting; otherwise, it searches all transcripts saved through `/ai/transcripts`. The application retrieves relevant chunks, then uses the local Ollama model through Spring AI `ChatClient` to draft an answer grounded in those chunks. If no usable transcript evidence is found and no meeting ID was supplied, it may give general guidance when the question can be answered without inventing meeting-specific facts. That answer explicitly says transcript information was unavailable and has an empty `sources` list. Questions about what a particular person or team said, decided, or did continue to abstain when transcripts do not contain the answer. The response includes the answer and source metadata:

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

For a general question that has no supporting transcript evidence, the response is labeled in the answer itself, for example:

```json
{
  "answer": "There is no information available in the saved transcripts for this question. Here is a general response: Teams can improve meeting transcription by reducing background noise, using a clear microphone, and checking speaker labels against the recording.",
  "meeting_id": null,
  "sources": []
}
```

The model is instructed to answer from retrieved evidence and avoid inventing missing details. Percentage changes are calculated from matching baseline and change values when both are present in the same evidence chunk. If no relevant transcript evidence is found, meeting-scoped questions abstain; unscoped questions receive a clearly labeled general response. When a question depends on unavailable private or meeting-specific facts, the model states that it cannot determine those facts instead of guessing.

## Q&A reliability

- The primary similarity threshold remains `0.55`; the lower fallback remains `0.45`.
- If primary retrieval yields fewer than two distinct chunks, the service asks the local ChatClient for up to three meaning-preserving search rewrites, searches those variants, and merges the results. It then retries at `0.45`; if evidence remains sparse, it retries at `0.20`. Unscoped results are restricted to matches close to the best score.
- Action and decision questions return matching transcript sentences directly. Yes/no questions require a directly matching sentence; relationship questions require an explicit sentence naming the requested subject and relationship. These paths abstain when the evidence does not directly support an answer.
- ChatClient generation uses a typed structured response. Every exact quote must appear in retrieved evidence, match the original question or one of its search rewrites, and retain any explicit date or numeric constraint in the question. When quote validation fails, generation gets one correction attempt; invalid or unsupported transcript answers are rejected. For unscoped questions only, a separate general-response step then answers without transcript evidence and prefixes the answer with an explicit transcript-unavailable notice. It must not invent private or meeting-specific facts. Returned source metadata is limited to chunks containing the supporting sentences or quotes. These checks reduce unsupported answers, but they are not a complete semantic entailment proof.
- Retrieval recognizes broad question shapes such as definitions, actions, decisions, plans, and subject-specific relationships. These routes do not assume a particular business domain, product, incident type, or subject vocabulary.
- Transcript ingestion creates or refreshes a compact meeting summary with verbatim evidence excerpts. If a corpus-wide overview is requested, the model signals that scope in its structured response, and the service loads one summary per meeting instead of relying on vector top-K coverage. Older meetings are summarized and cached on demand. Ordinary questions continue to use transcript-chunk retrieval; a supplied meeting ID remains a hard scope.
- Corpus-wide answers are checked against the stored meeting-summary evidence, with source metadata returned for each meeting that supports the answer. If any existing meeting summary cannot be grounded during backfill, the service abstains rather than presenting a partial collection summary as complete.
- In a corpus-wide response, a source with `chunk_index: -1` refers to that meeting's verified summary; `parent_document_id` points to the transcript document from which it was produced.
- Generic query rewriting is a sparse-retrieval fallback, so new paraphrases do not each require a new hard-coded question category. The rewrite call adds latency only when the initial search returns fewer than two distinct chunks.
- Repeated uploads of the same transcript to the same meeting receive deterministic chunk IDs. pgvector upserts by ID, so a network retry does not create another copy of those chunks. Uploading changed text still creates new chunk IDs; the service does not delete older meeting content.
- The generation temperature is `0.1` for more repeatable answers. The similarity thresholds and 350-token chunking are unchanged.
- `/ai/qna` logs the complete request and response at INFO. Spring AI's `SimpleLoggerAdvisor` logs model prompts and responses at DEBUG, which can include full transcript evidence. Set `logging.level.org.springframework.ai.chat.client.advisor=INFO` to hide model prompt/response details. `SATHI_LOG_CONTENT` controls the service's own full chunk and rewrite logs.
- Structured model output is validated against its Java schema. If the model returns malformed output, Spring AI may retry, which can increase response time. Retrieval augmentation adds the exact evidence chunks already selected and validated by SĀTHI; it does not issue another vector search.
- Supplying `conversation_id` enables an in-memory window of up to eight messages for follow-up questions. Memory is separated by meeting scope and conversation ID, is lost on application restart, and is not used when the field is omitted. A conversation ID should be reused only within one conversation.
- SafeGuardAdvisor uses the local list `SathiConstants.SAFE_GUARD_SENSITIVE_WORDS`. It currently contains `confidential`; add or remove entries in that constant. Matching is case-insensitive and checked before retrieval, and advisor blocks return a restriction message rather than a transcript-not-found answer.
- The controller and Q&A service emit focused DEBUG logs for query planning, per-variant vector search counts/scores/latency, evidence selection, model support flags, quote validation, and source metadata. Exact query variants, transcript chunks, and generated answers remain gated by `SATHI_LOG_CONTENT=true`.
- Health is available at `GET /actuator/health` and includes PostgreSQL plus an Ollama `/api/tags` reachability check with a three-second request timeout. Liveness/readiness are available at `/actuator/health/liveness` and `/actuator/health/readiness`. Micrometer metrics are available at `GET /actuator/metrics`, and Prometheus can scrape `GET /actuator/prometheus`.

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
