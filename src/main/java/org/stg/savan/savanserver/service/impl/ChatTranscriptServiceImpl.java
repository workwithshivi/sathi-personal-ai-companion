package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.util.MeetingIds;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatTranscriptServiceImpl implements ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptServiceImpl.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final ChatClient chatClient;

    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.chatClient = chatClient;
    }

    @Override
    public String saveTranscript(String text, String device, String requestedMeetingId) {
        String normalizedText = text.trim();
        String normalizedDevice = device == null || device.isBlank()
                ? SathiConstants.DEFAULT_CHAT_DEVICE : device.trim();
        String meetingId = MeetingIds.createOrPreserve(requestedMeetingId);

        Document source = new Document(normalizedText, Map.of(
                "received_at", Instant.now().toString(),
                "device", normalizedDevice,
                "memory_type", MemoryType.TRANSCRIPT.getMetadataValue(),
                "meeting_id", meetingId,
                "pipeline", SathiConstants.CHAT_PIPELINE));
        List<Document> chunks = textSplitter.apply(List.of(source));

        vectorStore.add(chunks);
        log.info("Stored ChatClient transcript | meeting_id={} | chunks={} | device={}",
                meetingId, chunks.size(), normalizedDevice);
        return meetingId;
    }

    @Override
    public ChatAnswerResult answerQuestion(String question, String meetingId) {
        String normalizedQuestion = question.trim();
        String normalizedMeetingId = MeetingIds.normalizeOptional(meetingId);
        QueryIntentRouter.PlannedQuery plannedQuery = QueryIntentRouter.plan(normalizedQuestion);
        String scope = normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId;

        log.info("ChatClient retrieval started | intent={} | scope={} | threshold={} | query_variants={}",
                plannedQuery.intent(), scope, SathiConstants.SIMILARITY_THRESHOLD,
                plannedQuery.searchQueries().size());
        List<Document> retrieved = search(plannedQuery.searchQueries(), normalizedMeetingId,
                SathiConstants.SIMILARITY_THRESHOLD);
        if (retrieved.isEmpty()) {
            log.info("No matches at primary threshold; retrying retrieval | scope={} | threshold={}",
                    scope, SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            retrieved = search(plannedQuery.searchQueries(), normalizedMeetingId,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
        }

        List<Document> contextDocuments = TranscriptDocumentSupport.deduplicateAndLimit(
                retrieved, SathiConstants.CHAT_RESULT_COUNT);
        if (contextDocuments.isEmpty()) {
            log.info("No ChatClient transcript context found after fallback | scope={}", scope);
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
        }

        String requiredSubject = plannedQuery.requiredSubject();
        if (requiredSubject != null && contextDocuments.stream()
                .noneMatch(document -> containsTerm(document.getText(), requiredSubject))) {
            log.info("Retrieved incident evidence does not mention the requested impact subject | scope={} | subject={}",
                    scope, requiredSubject);
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
        }

        var sources = contextDocuments.stream()
                .map(TranscriptDocumentSupport::toSource)
                .toList();
        String context = contextDocuments.stream()
                .map(TranscriptDocumentSupport::toTraceableContext)
                .collect(Collectors.joining("\n\n"));

        var deterministicAnswer = EvidenceCalculation.calculateResponseTimeReduction(
                normalizedQuestion, contextDocuments.stream().map(Document::getText).toList());
        if (deterministicAnswer.isPresent()) {
            log.info("Calculated response-time reduction from retrieved transcript evidence | scope={} | sources={}",
                    normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId,
                    sources.size());
            return new ChatAnswerResult(deterministicAnswer.get(), normalizedMeetingId, sources);
        }

        log.info("Retrieved {} unique ChatClient evidence chunks | scope={} | best_score={}",
                sources.size(), scope, sources.getFirst().score());

        long startedAt = System.nanoTime();
        String answer = chatClient.prompt()
                .system(SathiConstants.CHAT_SYSTEM_PROMPT)
                .user(user -> user.text("""
                        Question:
                        {question}

                        Retrieved transcript evidence:
                        {context}
                        """)
                        .param("question", normalizedQuestion)
                        .param("context", context))
                .call()
                .content();

        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("Chat model returned an empty answer");
        }

        log.info("Generated ChatClient answer | scope={} | evidence_chunks={} | elapsed_ms={}",
                normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId,
                sources.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return new ChatAnswerResult(answer.trim(), normalizedMeetingId, sources);
    }

    private List<Document> search(List<String> queries, String meetingId, double threshold) {
        List<Document> matches = new ArrayList<>();
        for (String query : queries) {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(SathiConstants.CHAT_RESULT_COUNT)
                    .similarityThreshold(threshold)
                    .filterExpression(meetingIdFilter(meetingId))
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            if (results != null) {
                matches.addAll(results);
            }
        }
        return matches;
    }

    private static String meetingIdFilter(String meetingId) {
        String pipelineFilter = "pipeline == '" + SathiConstants.CHAT_PIPELINE + "'";
        return meetingId == null
                ? pipelineFilter
                : pipelineFilter + " && meeting_id == '" + meetingId + "'";
    }

    private static boolean containsTerm(String text, String term) {
        return Pattern.compile("(?iu)\\b" + Pattern.quote(term) + "\\b")
                .matcher(text == null ? "" : text)
                .find();
    }

}
