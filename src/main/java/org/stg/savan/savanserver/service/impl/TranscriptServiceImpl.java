package org.stg.savan.savanserver.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.model.RetrievalResult;
import org.stg.savan.savanserver.model.RetrievalSource;
import org.stg.savan.savanserver.service.TranscriptService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
public class TranscriptServiceImpl implements TranscriptService {
    private static final int RESULT_COUNT = 3;
    private static final int UNFILTERED_SEARCH_COUNT = 12;
    private static final double SIMILARITY_THRESHOLD = 0.55;
    private static final Pattern MEETING_ID_PATTERN = Pattern.compile(
            "meeting-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Logger log =
            LoggerFactory.getLogger(TranscriptServiceImpl.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public TranscriptServiceImpl(VectorStore vectorStore, TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    @Override
    public String saveTranscript(String text, String device) throws IOException {

        String normalizedText = text.trim();

        String normalizedDevice = device == null || device.isBlank()
                ? "raspberry-pi"
                : device;

        String receivedAt = Instant.now().toString();
        String meetingId = "meeting-" + UUID.randomUUID();

        log.info("Saving transcript from device: {}", normalizedDevice);

        Document document = new Document(
                normalizedText,
                Map.of(
                        "received_at", receivedAt,
                        "device", normalizedDevice,
                        "memory_type", MemoryType.TRANSCRIPT.getMetadataValue(),
                        "meeting_id", meetingId
                )
        );

        List<Document> chunks = textSplitter.apply(List.of(document));

        log.info("Transcript split into {} chunks", chunks.size());

        vectorStore.add(chunks);

        log.info("Transcript stored as meeting {} with {} chunks", meetingId, chunks.size());
        return meetingId;
    }

    @Override
    public String answerQuestion(String question) {
        return retrieve(question, null).context();
    }

    @Override
    public RetrievalResult retrieve(String question, String meetingId) {
        String normalizedQuestion = question.trim();
        String normalizedMeetingId = normalizeMeetingId(meetingId);
        QueryIntentRouter.PlannedQuery plannedQuery = QueryIntentRouter.plan(normalizedQuestion);
        long startedAt = System.nanoTime();

        int searchLimit = normalizedMeetingId == null ? UNFILTERED_SEARCH_COUNT : RESULT_COUNT;
        log.info("Q&A retrieval started | intent={} | scope={} | threshold={} | top_k={}",
                plannedQuery.intent(),
                normalizedMeetingId == null ? "all_meetings" : normalizedMeetingId,
                SIMILARITY_THRESHOLD,
                searchLimit);
        log.debug("Q&A question: {}", normalizedQuestion);

        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(plannedQuery.searchText())
                .topK(searchLimit)
                .similarityThreshold(SIMILARITY_THRESHOLD);
        if (normalizedMeetingId != null) {
            searchBuilder.filterExpression("meeting_id == '" + normalizedMeetingId + "'");
        }

        List<Document> retrieved = vectorStore.similaritySearch(searchBuilder.build());
        List<Document> documents = deduplicateAndLimit(retrieved);

        if (documents == null || documents.isEmpty()) {
            log.info("No relevant memories found | intent={} | elapsed_ms={}",
                    plannedQuery.intent(), elapsedMillis(startedAt));
            return new RetrievalResult(
                    "I could not find anything relevant in the meeting transcript.",
                    plannedQuery.intent(),
                    List.of());
        }

        List<RetrievalSource> sources = documents.stream()
                .map(TranscriptServiceImpl::toSource)
                .toList();
        String context = documents.stream()
                .map(TranscriptServiceImpl::toTraceableContext)
                .collect(Collectors.joining("\n\n"));

        log.info("Retrieved {} unique memories | elapsed_ms={}",
                documents.size(), elapsedMillis(startedAt));
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = document.getMetadata();
            log.info("Result {}/{} | meeting_id={} | chunk_index={} | score={} | parent_document_id={}",
                    index + 1,
                    documents.size(),
                    metadata.get("meeting_id"),
                    metadata.get("chunk_index"),
                    document.getScore(),
                    metadata.get("parent_document_id"));
            log.debug("Result {}/{} preview: {}", index + 1, documents.size(), preview(document.getText()));
        }

        return new RetrievalResult(context, plannedQuery.intent(), sources);
    }

    private static String normalizeMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            return null;
        }
        String normalized = meetingId.trim();
        if (!MEETING_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("meeting_id must be a meeting UUID returned by transcript ingestion");
        }
        return normalized;
    }

    private static List<Document> deduplicateAndLimit(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<String> seenContent = new HashSet<>();
        return documents.stream()
                .filter(document -> seenContent.add(normalizeText(document.getText())))
                .limit(RESULT_COUNT)
                .toList();
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static RetrievalSource toSource(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievalSource(
                (String) metadata.get("meeting_id"),
                asInteger(metadata.get("chunk_index")),
                (String) metadata.get("parent_document_id"),
                document.getScore());
    }

    private static String toTraceableContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return "[meeting_id=" + metadata.get("meeting_id")
                + ", chunk_index=" + metadata.get("chunk_index")
                + ", parent_document_id=" + metadata.get("parent_document_id") + "]\n"
                + document.getText();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value.toString());
    }

    private static String preview(String text) {
        String singleLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 180
                ? singleLine
                : singleLine.substring(0, 180) + "…";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
