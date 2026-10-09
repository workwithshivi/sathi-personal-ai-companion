package org.stg.savan.savanserver.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.model.RetrievalResult;
import org.stg.savan.savanserver.model.RetrievalSource;
import org.stg.savan.savanserver.service.TranscriptService;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.util.MeetingIds;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
public class TranscriptServiceImpl implements TranscriptService {
    private static final Logger log =
            LoggerFactory.getLogger(TranscriptServiceImpl.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public TranscriptServiceImpl(VectorStore vectorStore, TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    @Override
    public String saveTranscript(String text, String device, String requestedMeetingId) {

        String normalizedText = text.trim();

        String normalizedDevice = device == null || device.isBlank()
                ? SathiConstants.DEFAULT_TRANSCRIPT_DEVICE
                : device;

        String receivedAt = Instant.now().toString();
        String meetingId = MeetingIds.createOrPreserve(requestedMeetingId);

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
    public RetrievalResult retrieve(String question, String meetingId) {
        String normalizedQuestion = question.trim();
        String normalizedMeetingId = MeetingIds.normalizeOptional(meetingId);
        QueryIntentRouter.PlannedQuery plannedQuery = QueryIntentRouter.plan(normalizedQuestion);
        long startedAt = System.nanoTime();

        int searchLimit = normalizedMeetingId == null
                ? SathiConstants.UNFILTERED_TRANSCRIPT_SEARCH_COUNT
                : SathiConstants.TRANSCRIPT_RESULT_COUNT;
        List<String> queryVariants = plannedQuery.searchQueries();
        log.info("Q&A retrieval started | intent={} | scope={} | threshold={} | top_k={} | query_variants={}",
                plannedQuery.intent(),
                normalizedMeetingId == null ? "all_meetings" : normalizedMeetingId,
                SathiConstants.SIMILARITY_THRESHOLD,
                searchLimit,
                queryVariants.size());
        log.debug("Q&A question: {}", normalizedQuestion);

        List<Document> retrieved = search(queryVariants, normalizedMeetingId, searchLimit,
                SathiConstants.SIMILARITY_THRESHOLD);
        if (retrieved.isEmpty()) {
            log.info("No transcript matches at primary threshold; retrying retrieval | scope={} | threshold={}",
                    normalizedMeetingId == null ? "all_meetings" : normalizedMeetingId,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            retrieved = search(queryVariants, normalizedMeetingId, searchLimit,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
        }

        List<Document> documents = TranscriptDocumentSupport.deduplicateAndLimit(
                retrieved, SathiConstants.TRANSCRIPT_RESULT_COUNT);

        if (documents.isEmpty()) {
            log.info("No relevant memories found | intent={} | elapsed_ms={}",
                    plannedQuery.intent(), elapsedMillis(startedAt));
            return new RetrievalResult(
                    "I could not find anything relevant in the meeting transcript.",
                    plannedQuery.intent(),
                    List.of());
        }

        List<RetrievalSource> sources = documents.stream()
                .map(TranscriptDocumentSupport::toSource)
                .toList();
        String context = documents.stream()
                .map(TranscriptDocumentSupport::toTraceableContext)
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

    private static String preview(String text) {
        String singleLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 180
                ? singleLine
                : singleLine.substring(0, 180) + "…";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private List<Document> search(List<String> queries, String meetingId, int topK, double threshold) {
        List<Document> results = new ArrayList<>();
        for (String query : queries) {
            SearchRequest.Builder request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);
            if (meetingId != null) {
                request.filterExpression("meeting_id == '" + meetingId + "'");
            }
            List<Document> matches = vectorStore.similaritySearch(request.build());
            if (matches != null) {
                results.addAll(matches);
            }
        }
        return results;
    }
}
