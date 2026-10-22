package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.GroundedChatAnswer;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.stg.savan.savanserver.util.MeetingIds;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatTranscriptServiceImpl implements ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptServiceImpl.class);
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final ChatClient chatClient;

    @Value("${sathi.logging.include-content:false}")
    private boolean includeContentInLogs;

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
        long startedAt = System.nanoTime();

        Document source = new Document(normalizedText, Map.of(
                "received_at", Instant.now().toString(),
                "device", normalizedDevice,
                "memory_type", MemoryType.TRANSCRIPT.getMetadataValue(),
                "meeting_id", meetingId,
                "pipeline", SathiConstants.CHAT_PIPELINE));
        List<Document> chunks = new ArrayList<>(textSplitter.apply(List.of(source)));
        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("meeting_id", meetingId);
            metadata.put("pipeline", SathiConstants.CHAT_PIPELINE);
            chunk = Document.builder()
                    .id(QnaEvidenceSupport.stableChunkId(meetingId, index, chunk.getText()))
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build();
            chunks.set(index, chunk);
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Transcript storage chunk {}/{} | meeting_id={} | content=\n{}",
                    index + 1, chunks.size(), meetingId, chunk.getText());
        }

        vectorStore.add(chunks);
        ApiRequestLogSupport.info(log,
                "Stored ChatClient transcript | meeting_id={} | chunks={} | device={} | elapsed_ms={}",
                meetingId, chunks.size(), normalizedDevice, ApiRequestLogSupport.elapsedMillis(startedAt));
        return meetingId;
    }

    @Override
    public ChatAnswerResult answerQuestion(String question, String meetingId) {
        String normalizedQuestion = question.trim();
        String normalizedMeetingId = MeetingIds.normalizeOptional(meetingId);
        QueryIntentRouter.PlannedQuery plannedQuery = QueryIntentRouter.plan(normalizedQuestion);
        String scope = normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId;

        ApiRequestLogSupport.info(log,
                "ChatClient retrieval started | intent={} | scope={} | threshold={} | query_variants={}",
                plannedQuery.intent(), scope, SathiConstants.SIMILARITY_THRESHOLD,
                plannedQuery.searchQueries().size());
        for (int index = 0; index < plannedQuery.searchQueries().size(); index++) {
            String searchQuery = plannedQuery.searchQueries().get(index);
            if (includeContentInLogs) {
                ApiRequestLogSupport.debug(log, "Q&A planned query | intent={} | variant={}/{} | query={}",
                        plannedQuery.intent(), index + 1, plannedQuery.searchQueries().size(), searchQuery);
            } else {
                ApiRequestLogSupport.debug(log,
                        "Q&A planned query | intent={} | variant={}/{} | query_characters={}",
                        plannedQuery.intent(), index + 1, plannedQuery.searchQueries().size(), searchQuery.length());
            }
        }
        long retrievalStartedAt = System.nanoTime();
        List<Document> retrieved = search(plannedQuery.searchQueries(), normalizedMeetingId,
                SathiConstants.SIMILARITY_THRESHOLD);
        int primaryCount = TranscriptDocumentSupport.deduplicateAndLimit(
                retrieved, SathiConstants.CHAT_RESULT_COUNT).size();
        ApiRequestLogSupport.debug(log,
                "Primary retrieval summary | scope={} | raw_hits={} | distinct_chunks={} | threshold={}",
                scope, retrieved.size(), primaryCount, SathiConstants.SIMILARITY_THRESHOLD);
        if (primaryCount < SathiConstants.CHAT_MIN_PRIMARY_RESULTS) {
            ApiRequestLogSupport.info(log,
                    "Primary retrieval returned limited evidence; retrying and merging fallback results | scope={} | primary_chunks={} | threshold={}",
                    scope, primaryCount, SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            List<Document> fallback = search(plannedQuery.searchQueries(), normalizedMeetingId,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            retrieved.addAll(fallback);
            ApiRequestLogSupport.debug(log,
                    "Merged retrieval summary | scope={} | primary_hits={} | fallback_hits={} | merged_distinct_chunks={}",
                    scope, retrieved.size() - fallback.size(), fallback.size(),
                    TranscriptDocumentSupport.deduplicateAndLimit(retrieved, SathiConstants.CHAT_RESULT_COUNT).size());
            ApiRequestLogSupport.info(log,
                    "Fallback retrieval completed | scope={} | fallback_hits={} | elapsed_ms={}",
                    scope, fallback.size(), ApiRequestLogSupport.elapsedMillis(retrievalStartedAt));
        } else {
            ApiRequestLogSupport.info(log,
                    "Primary retrieval completed | scope={} | distinct_chunks={} | elapsed_ms={}",
                    scope, primaryCount, ApiRequestLogSupport.elapsedMillis(retrievalStartedAt));
        }

        List<Document> contextDocuments = TranscriptDocumentSupport.deduplicateAndLimit(
                retrieved, SathiConstants.CHAT_RESULT_COUNT);
        if (normalizedMeetingId == null) {
            List<Document> beforeRelevanceFilter = contextDocuments;
            contextDocuments = TranscriptDocumentSupport.keepNearBestScore(
                    contextDocuments, SathiConstants.UNSCOPED_RELEVANCE_SCORE_MARGIN);
            ApiRequestLogSupport.debug(log,
                    "Applied unscoped evidence relevance filter | before={} | after={} | score_gap={}",
                    beforeRelevanceFilter.size(), contextDocuments.size(),
                    SathiConstants.UNSCOPED_RELEVANCE_SCORE_MARGIN);
        }
        if (contextDocuments.isEmpty()) {
            ApiRequestLogSupport.info(log, "No ChatClient transcript context found after fallback | scope={}", scope);
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
        }

        for (int index = 0; index < contextDocuments.size(); index++) {
            Document document = contextDocuments.get(index);
            ApiRequestLogSupport.debug(log,
                    "Q&A evidence selected | rank={}/{} | scope={} | metadata={} | score={} | characters={}",
                    index + 1, contextDocuments.size(), scope, document.getMetadata(), document.getScore(),
                    document.getText().length());
            ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                    "Q&A evidence content | rank={}/{} | scope={} | metadata={} | content=\n{}",
                    index + 1, contextDocuments.size(), scope, document.getMetadata(), document.getText());
        }

        String requiredSubject = plannedQuery.requiredSubject();
        if (requiredSubject != null && contextDocuments.stream()
                .noneMatch(document -> QnaEvidenceSupport.containsTerm(document.getText(), requiredSubject))) {
            ApiRequestLogSupport.info(log,
                    "Retrieved evidence does not mention the requested relation subject | scope={} | subject={}",
                    scope, requiredSubject);
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
        }

        var sources = contextDocuments.stream()
                .map(TranscriptDocumentSupport::toSource)
                .toList();
        String context = contextDocuments.stream()
                .map(TranscriptDocumentSupport::toTraceableContext)
                .collect(Collectors.joining("\n\n"));

        var definitionAnswer = QnaEvidenceSupport.answerDefinitionFromEvidence(normalizedQuestion, contextDocuments);
        if (definitionAnswer.isPresent()) {
            ApiRequestLogSupport.info(log,
                    "Answered entity definition directly from transcript evidence | scope={} | answer_characters={}",
                    scope, definitionAnswer.get().length());
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Exact deterministic /ai/qna answer | scope={} | answer=\n{}", scope, definitionAnswer.get());
            return new ChatAnswerResult(definitionAnswer.get(), normalizedMeetingId, sources);
        }

        var deterministicAnswer = EvidenceCalculation.calculatePercentageChange(
                normalizedQuestion, contextDocuments.stream().map(Document::getText).toList());
        if (deterministicAnswer.isPresent()) {
            ApiRequestLogSupport.info(log,
                    "Calculated percentage change from retrieved transcript evidence | scope={} | sources={}",
                    scope, sources.size());
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Exact deterministic /ai/qna answer | scope={} | answer=\n{}", scope, deterministicAnswer.get());
            return new ChatAnswerResult(deterministicAnswer.get(), normalizedMeetingId, sources);
        }

        ApiRequestLogSupport.info(log, "Retrieved {} unique ChatClient evidence chunks | scope={} | best_score={}",
                sources.size(), scope, sources.getFirst().score());

        long startedAt = System.nanoTime();
        GroundedChatAnswer generated = chatClient.prompt()
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
                .entity(GroundedChatAnswer.class, spec -> spec.useProviderStructuredOutput());

        if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
            throw new IllegalStateException("Chat model returned an empty structured answer");
        }
        QnaEvidenceSupport.EvidenceQuoteCheck quoteCheck =
                QnaEvidenceSupport.checkEvidenceQuotes(generated.evidenceQuotes(), contextDocuments);
        ApiRequestLogSupport.debug(log,
                "Structured Q&A response validated | scope={} | model_supported={} | valid_quotes={}/{} | answer_characters={}",
                scope, generated.supportedByEvidence(), quoteCheck.validCount(), quoteCheck.totalCount(),
                generated.answer().length());
        if (!generated.supportedByEvidence() || !quoteCheck.valid()) {
            ApiRequestLogSupport.info(log,
                    "Rejected generated answer | scope={} | model_supported={} | valid_quotes={}/{} | rejection_reason={}",
                    scope, generated.supportedByEvidence(), quoteCheck.validCount(), quoteCheck.totalCount(),
                    !generated.supportedByEvidence() ? "model_marked_unsupported" : "quote_not_found_in_sources");
            ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                    "Rejected generated answer content | scope={} | answer={} | evidence_quotes={}",
                    scope, generated.answer(), generated.evidenceQuotes());
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
        }
        String answer = generated.answer().trim();

        ApiRequestLogSupport.info(log,
                "Generated ChatClient answer | scope={} | evidence_chunks={} | elapsed_ms={}",
                scope, sources.size(), ApiRequestLogSupport.elapsedMillis(startedAt));
        ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                "Exact generated /ai/qna answer | scope={} | answer=\n{}", scope, answer);
        return new ChatAnswerResult(answer.trim(), normalizedMeetingId, sources);
    }

    private List<Document> search(List<String> queries, String meetingId, double threshold) {
        List<Document> matches = new ArrayList<>();
        int queryIndex = 0;
        for (String query : queries) {
            queryIndex++;
            long searchStartedAt = System.nanoTime();
            String scope = meetingId == null ? "all_chat_meetings" : meetingId;
            ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                    "Vector search query | scope={} | threshold={} | variant={}/{} | query={}",
                    scope, threshold, queryIndex, queries.size(), query);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(SathiConstants.CHAT_RESULT_COUNT)
                    .similarityThreshold(threshold)
                    .filterExpression(QnaEvidenceSupport.meetingIdFilter(meetingId))
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            if (results != null) {
                matches.addAll(results);
            }
            Double bestScore = results == null ? null : results.stream()
                    .map(Document::getScore)
                    .filter(java.util.Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(null);
            ApiRequestLogSupport.debug(log,
                    "Vector search completed | scope={} | threshold={} | variant={}/{} | hits={} | best_score={} | elapsed_ms={}",
                    scope, threshold, queryIndex, queries.size(), results == null ? 0 : results.size(), bestScore,
                    ApiRequestLogSupport.elapsedMillis(searchStartedAt));
            if (results != null) {
                for (int index = 0; index < results.size(); index++) {
                    ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                            "Vector search hit | scope={} | threshold={} | variant={}/{} | rank={} | score={} | metadata={} | content=\n{}",
                            scope, threshold, queryIndex, queries.size(), index + 1, results.get(index).getScore(),
                            results.get(index).getMetadata(), results.get(index).getText());
                }
            }
        }
        return matches;
    }

}
