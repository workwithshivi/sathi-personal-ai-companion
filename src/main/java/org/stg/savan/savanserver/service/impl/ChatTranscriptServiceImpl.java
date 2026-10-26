package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.GroundedChatAnswer;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.stg.savan.savanserver.service.support.MeetingMemoryRepository;
import org.stg.savan.savanserver.util.MeetingIds;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Coordinates Q&A retrieval, evidence policy, and response validation; ingestion, summary generation, and model prompting are delegated to focused collaborators. */
@Service
public class ChatTranscriptServiceImpl implements ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptServiceImpl.class);
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final VectorStore vectorStore;
    private final TranscriptIngestionService transcriptIngestionService;
    private final MeetingSummaryService meetingSummaryService;
    private final QnaChatClientSupport qnaChatClientSupport;
    private final DeterministicEvidenceAnswerer deterministicEvidenceAnswerer;

    @Value("${sathi.logging.include-content:false}")
    private boolean includeContentInLogs;

    /** Injects focused transcript-ingestion, summary, and Q&A model components. */
    @Autowired
    public ChatTranscriptServiceImpl(
            TranscriptIngestionService transcriptIngestionService,
            MeetingSummaryService meetingSummaryService,
            VectorStore vectorStore,
            QnaChatClientSupport qnaChatClientSupport,
            DeterministicEvidenceAnswerer deterministicEvidenceAnswerer) {
        this.transcriptIngestionService = transcriptIngestionService;
        this.meetingSummaryService = meetingSummaryService;
        this.vectorStore = vectorStore;
        this.qnaChatClientSupport = qnaChatClientSupport;
        this.deterministicEvidenceAnswerer = deterministicEvidenceAnswerer;
    }

    /** Compatibility constructor used by existing focused service tests. */
    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient,
            MeetingMemoryRepository meetingMemoryRepository) {
        this(vectorStore, chatClient, meetingMemoryRepository,
                new QnaChatClientSupport(chatClient, null, null, null, null),
                new DeterministicEvidenceAnswerer(), textSplitter);
    }

    /** Compatibility constructor for tests that do not need a meeting repository. */
    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient) {
        this(vectorStore, textSplitter, chatClient, null);
    }

    /** Builds test-compatible delegates; production uses the Spring-injected collaborator constructor. */
    private ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            ChatClient chatClient,
            MeetingMemoryRepository meetingMemoryRepository,
            QnaChatClientSupport qnaChatClientSupport,
            DeterministicEvidenceAnswerer deterministicEvidenceAnswerer,
            TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.meetingSummaryService = new MeetingSummaryService(
                meetingMemoryRepository, vectorStore, new MeetingSummaryGenerator(chatClient));
        this.qnaChatClientSupport = qnaChatClientSupport;
        this.deterministicEvidenceAnswerer = deterministicEvidenceAnswerer;
        this.transcriptIngestionService = new TranscriptIngestionService(
                vectorStore, textSplitter, meetingMemoryRepository, new MeetingSummaryGenerator(chatClient));
    }

    /** Delegates transcript persistence to the ingestion component. */
    @Override
    public String saveTranscript(String text, String device, String requestedMeetingId) {
        return transcriptIngestionService.saveTranscript(text, device, requestedMeetingId, includeContentInLogs);
    }

    /** Preserves the two-argument service API and runs Q&A without conversation memory. */
    @Override
    public ChatAnswerResult answerQuestion(String question, String meetingId) {
        return answerQuestion(question, meetingId, null);
    }

    /** Orchestrates scoped retrieval, evidence-based answering, validation, and fallback behavior. */
    @Override
    public ChatAnswerResult answerQuestion(String question, String meetingId, String conversationId) {
        String normalizedQuestion = question.trim();
        String normalizedMeetingId = MeetingIds.normalizeOptional(meetingId);
        if (containsConfiguredSensitiveWord(normalizedQuestion)) {
            ApiRequestLogSupport.info(log,
                    "Blocked Q&A request by configured sensitive-word filter | meeting_id={}", normalizedMeetingId);
            return new ChatAnswerResult(SathiConstants.SAFE_GUARD_BLOCK_MESSAGE, normalizedMeetingId, List.of());
        }
        String scopedConversationId = normalizeConversationId(conversationId, normalizedMeetingId);
        QueryIntentRouter.PlannedQuery plannedQuery = QueryIntentRouter.plan(normalizedQuestion);
        LinkedHashSet<String> searchQueries = new LinkedHashSet<>(plannedQuery.searchQueries());
        String scope = normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId;
        if (scopedConversationId != null) {
            List<String> followUpQueries = expandFollowUpSearchQueries(normalizedQuestion, scopedConversationId);
            if (!followUpQueries.isEmpty()) {
                searchQueries.addAll(followUpQueries);
                ApiRequestLogSupport.info(log,
                        "Resolved follow-up references for retrieval | scope={} | conversation_id={} | variants={}",
                        scope, scopedConversationId, followUpQueries.size());
            }
        }

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
        List<Document> retrieved = search(List.copyOf(searchQueries), normalizedMeetingId,
                SathiConstants.SIMILARITY_THRESHOLD);
        boolean genericExpansionAttempted = false;
        int primaryCount = TranscriptDocumentSupport.deduplicateAndLimit(
                retrieved, SathiConstants.CHAT_RESULT_COUNT).size();
        ApiRequestLogSupport.debug(log,
                "Primary retrieval summary | scope={} | raw_hits={} | distinct_chunks={} | threshold={}",
                scope, retrieved.size(), primaryCount, SathiConstants.SIMILARITY_THRESHOLD);
        if (primaryCount < SathiConstants.CHAT_MIN_PRIMARY_RESULTS
                && plannedQuery.intent().equals("TRANSCRIPT")) {
            genericExpansionAttempted = true;
            List<String> expandedQueries = expandSearchQueries(normalizedQuestion);
            if (!expandedQueries.isEmpty()) {
                List<String> distinctExpansions = expandedQueries.stream()
                        .filter(query -> !searchQueries.contains(query))
                        .toList();
                if (!distinctExpansions.isEmpty()) {
                    searchQueries.addAll(distinctExpansions);
                    List<Document> expandedMatches = search(distinctExpansions, normalizedMeetingId,
                            SathiConstants.SIMILARITY_THRESHOLD);
                    retrieved.addAll(expandedMatches);
                    primaryCount = TranscriptDocumentSupport.deduplicateAndLimit(
                            retrieved, SathiConstants.CHAT_RESULT_COUNT).size();
                    ApiRequestLogSupport.debug(log,
                            "Generic query expansion completed | scope={} | variants={} | evidence_chunks={}",
                            scope, distinctExpansions.size(), primaryCount);
                }
            }
        }

        if (primaryCount < SathiConstants.CHAT_MIN_PRIMARY_RESULTS) {
            ApiRequestLogSupport.info(log,
                    "Primary retrieval returned limited evidence; retrying and merging fallback results | scope={} | primary_chunks={} | threshold={}",
                    scope, primaryCount, SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            List<Document> fallback = search(List.copyOf(searchQueries), normalizedMeetingId,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            retrieved.addAll(fallback);
            ApiRequestLogSupport.debug(log,
                    "Merged retrieval summary | scope={} | primary_hits={} | fallback_hits={} | merged_distinct_chunks={}",
                    scope, retrieved.size() - fallback.size(), fallback.size(),
                    TranscriptDocumentSupport.deduplicateAndLimit(retrieved, SathiConstants.CHAT_RESULT_COUNT).size());
            ApiRequestLogSupport.info(log,
                    "Fallback retrieval completed | scope={} | fallback_hits={} | elapsed_ms={}",
                    scope, fallback.size(), ApiRequestLogSupport.elapsedMillis(retrievalStartedAt));
            int mergedCount = TranscriptDocumentSupport.deduplicateAndLimit(
                    retrieved, SathiConstants.CHAT_RESULT_COUNT).size();
            if (mergedCount < SathiConstants.CHAT_MIN_PRIMARY_RESULTS) {
                ApiRequestLogSupport.info(log,
                        "Retrieval still sparse; retrying with last-resort threshold | scope={} | chunks={} | threshold={}",
                        scope, mergedCount, SathiConstants.RETRIEVAL_LAST_RESORT_SIMILARITY_THRESHOLD);
                List<Document> lastResort = search(List.copyOf(searchQueries), normalizedMeetingId,
                        SathiConstants.RETRIEVAL_LAST_RESORT_SIMILARITY_THRESHOLD);
                retrieved.addAll(lastResort);
                ApiRequestLogSupport.info(log,
                        "Last-resort retrieval completed | scope={} | hits={} | elapsed_ms={}",
                        scope, lastResort.size(), ApiRequestLogSupport.elapsedMillis(retrievalStartedAt));
            }
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
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
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
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }

        List<org.stg.savan.savanserver.model.RetrievalSource> sources = toSources(contextDocuments);
        String context = toContext(contextDocuments);

        DeterministicEvidenceAnswerer.Resolution deterministicResolution = deterministicEvidenceAnswerer.resolve(
                normalizedQuestion, plannedQuery.intent(), requiredSubject, normalizedMeetingId,
                contextDocuments, includeContentInLogs);
        if (deterministicResolution.terminal()) {
            return deterministicResolution.answer()
                    .orElseGet(() -> answerWithoutTranscript(normalizedQuestion, normalizedMeetingId));
        }

        ApiRequestLogSupport.info(log, "Retrieved {} unique ChatClient evidence chunks | scope={} | best_score={}",
                sources.size(), scope, sources.getFirst().score());

        long startedAt = System.nanoTime();
        GroundedChatAnswer generated = generateGroundedAnswer(
                normalizedQuestion, context, contextDocuments, false, scopedConversationId);

        if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
            throw new IllegalStateException("Chat model returned an empty structured answer");
        }
        if (Boolean.TRUE.equals(generated.blockedBySafeguard())) {
            return safeGuardBlockedAnswer(generated.answer(), normalizedMeetingId);
        }
        boolean corpusWideRequest = Boolean.TRUE.equals(generated.corpusWideRequest()) && normalizedMeetingId == null;
        if (corpusWideRequest) {
            List<Document> meetingSummaries = meetingSummaryService.loadAllOrBackfillMissing();
            if (meetingSummaries.isEmpty()) {
                ApiRequestLogSupport.info(log,
                        "Corpus-wide request has no saved meeting summaries or transcript chunks | scope={}", scope);
                return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, normalizedMeetingId, List.of());
            }
            ApiRequestLogSupport.info(log,
                    "Answering corpus-wide request from one stored summary per meeting | meetings={} | scope={}",
                    meetingSummaries.size(), scope);
            contextDocuments = meetingSummaries;
            sources = toSources(contextDocuments);
            context = toContext(contextDocuments);
            generated = generateGroundedAnswer(
                    normalizedQuestion, context, contextDocuments, false, scopedConversationId);
            if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
                throw new IllegalStateException("Chat model returned an empty corpus-wide summary answer");
            }
            if (Boolean.TRUE.equals(generated.blockedBySafeguard())) {
                return safeGuardBlockedAnswer(generated.answer(), normalizedMeetingId);
            }
        } else if (!Boolean.TRUE.equals(generated.supportedByEvidence())) {
            if (!genericExpansionAttempted && plannedQuery.intent().equals("TRANSCRIPT")) {
                List<String> expandedQueries = expandSearchQueries(normalizedQuestion);
                searchQueries.addAll(expandedQueries);
                genericExpansionAttempted = true;
            }
            List<Document> broaderMatches = search(List.copyOf(searchQueries), normalizedMeetingId,
                    SathiConstants.RETRIEVAL_FALLBACK_SIMILARITY_THRESHOLD);
            List<Document> expandedContext = new ArrayList<>(retrieved);
            expandedContext.addAll(broaderMatches);
            List<Document> broaderContextDocuments = TranscriptDocumentSupport.deduplicateAndLimit(
                    expandedContext, SathiConstants.CHAT_RESULT_COUNT);
            if (normalizedMeetingId == null) {
                broaderContextDocuments = TranscriptDocumentSupport.keepNearBestScore(
                        broaderContextDocuments, SathiConstants.UNSCOPED_RELEVANCE_SCORE_MARGIN);
            }
            boolean broaderEvidenceChanged = !broaderContextDocuments.stream()
                    .map(Document::getText)
                    .toList()
                    .equals(contextDocuments.stream().map(Document::getText).toList());
            if (broaderEvidenceChanged) {
                ApiRequestLogSupport.info(log,
                        "Answer unsupported by initial evidence; retrying with broader retrieval | scope={} | initial_chunks={} | broader_chunks={} | added_matches={}",
                        scope, contextDocuments.size(), broaderContextDocuments.size(), broaderMatches.size());
                contextDocuments = broaderContextDocuments;
                sources = toSources(contextDocuments);
                context = toContext(contextDocuments);
                generated = generateGroundedAnswer(
                        normalizedQuestion, context, contextDocuments, false, scopedConversationId);
                if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
                    throw new IllegalStateException("Chat model returned an empty structured answer on broader retrieval");
                }
                if (Boolean.TRUE.equals(generated.blockedBySafeguard())) {
                    return safeGuardBlockedAnswer(generated.answer(), normalizedMeetingId);
                }
            } else {
                ApiRequestLogSupport.info(log,
                        "Broader retrieval found no additional usable evidence | scope={} | initial_chunks={} | retrieved_matches={}",
                        scope, contextDocuments.size(), broaderMatches.size());
            }
        }
        QnaEvidenceSupport.EvidenceQuoteCheck quoteCheck =
                QnaEvidenceSupport.checkEvidenceQuotes(generated.evidenceQuotes(), contextDocuments);
        boolean quotesAddressQuestion = corpusWideRequest
                ? QnaEvidenceSupport.evidenceQuotesRespectQuestionConstraints(
                        normalizedQuestion, generated.evidenceQuotes())
                : QnaEvidenceSupport.evidenceQuotesAddressQuestion(
                        normalizedQuestion, generated.evidenceQuotes(), List.copyOf(searchQueries));
        if (Boolean.TRUE.equals(generated.supportedByEvidence()) && (!quoteCheck.valid() || !quotesAddressQuestion)) {
            ApiRequestLogSupport.info(log,
                    "Retrying grounded answer once because evidence validation failed | scope={} | valid_quotes={}/{} | quotes_address_question={}",
                    scope, quoteCheck.validCount(), quoteCheck.totalCount(), quotesAddressQuestion);
            generated = generateGroundedAnswer(
                    normalizedQuestion, context, contextDocuments, true, scopedConversationId);
            if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
                throw new IllegalStateException("Chat model returned an empty structured answer on validation retry");
            }
            if (Boolean.TRUE.equals(generated.blockedBySafeguard())) {
                return safeGuardBlockedAnswer(generated.answer(), normalizedMeetingId);
            }
            quoteCheck = QnaEvidenceSupport.checkEvidenceQuotes(generated.evidenceQuotes(), contextDocuments);
            quotesAddressQuestion = corpusWideRequest
                    ? QnaEvidenceSupport.evidenceQuotesRespectQuestionConstraints(
                            normalizedQuestion, generated.evidenceQuotes())
                    : QnaEvidenceSupport.evidenceQuotesAddressQuestion(
                            normalizedQuestion, generated.evidenceQuotes(), List.copyOf(searchQueries));
        }
        ApiRequestLogSupport.debug(log,
                "Structured Q&A response validated | scope={} | model_supported={} | valid_quotes={}/{} | quotes_address_question={} | answer_characters={}",
                scope, Boolean.TRUE.equals(generated.supportedByEvidence()), quoteCheck.validCount(), quoteCheck.totalCount(),
                quotesAddressQuestion, generated.answer().length());
        if (!Boolean.TRUE.equals(generated.supportedByEvidence()) || !quoteCheck.valid() || !quotesAddressQuestion) {
            ApiRequestLogSupport.info(log,
                    "Rejected generated answer | scope={} | model_supported={} | valid_quotes={}/{} | quotes_address_question={} | rejection_reason={}",
                    scope, Boolean.TRUE.equals(generated.supportedByEvidence()), quoteCheck.validCount(), quoteCheck.totalCount(),
                    quotesAddressQuestion,
                    !Boolean.TRUE.equals(generated.supportedByEvidence()) ? "model_marked_unsupported"
                            : !quoteCheck.valid() ? "quote_not_found_in_sources" : "quote_not_relevant_to_question");
            ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                    "Rejected generated answer content | scope={} | answer={} | evidence_quotes={}",
                    scope, generated.answer(), generated.evidenceQuotes());
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }
        String answer = QnaEvidenceSupport.completeFragmentAnswer(
                generated.answer().trim(), generated.evidenceQuotes()).trim();
        List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                generated.evidenceQuotes(), contextDocuments);

        ApiRequestLogSupport.info(log,
                "Generated ChatClient answer | scope={} | evidence_chunks={} | elapsed_ms={}",
                scope, sources.size(), ApiRequestLogSupport.elapsedMillis(startedAt));
        ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                "Exact generated /ai/qna answer | scope={} | answer=\n{}", scope, answer);
        return new ChatAnswerResult(answer.trim(), normalizedMeetingId, toSources(supportingDocuments));
    }

    /** Converts selected evidence documents into the public source-reference model. */
    private static List<org.stg.savan.savanserver.model.RetrievalSource> toSources(List<Document> documents) {
        return documents.stream().map(TranscriptDocumentSupport::toSource).toList();
    }

    /** Abstains for meeting-scoped misses or labels general model output for unscoped misses. */
    private ChatAnswerResult answerWithoutTranscript(String question, String meetingId) {
        if (meetingId != null) {
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, meetingId, List.of());
        }
        return qnaChatClientSupport.generateGeneralAnswer(question)
                .map(answer -> {
                    String prefixedAnswer = SathiConstants.GENERAL_RESPONSE_PREFIX + answer;
                    ApiRequestLogSupport.info(log,
                            "Generated general ChatClient response after transcript abstention | sources=0 | answer_characters={}",
                            prefixedAnswer.length());
                    return new ChatAnswerResult(prefixedAnswer, null, List.of());
                })
                .orElseGet(() -> {
                    ApiRequestLogSupport.info(log,
                            "General response generation returned no answer | scope=all_chat_meetings");
                    return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, null, List.of());
                });
    }

    /** Checks whether a question contains any configured restricted term. */
    private boolean containsConfiguredSensitiveWord(String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return SathiConstants.SAFE_GUARD_SENSITIVE_WORDS.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedQuestion::contains);
    }

    /** Converts an advisor block into the API response shape. */
    private ChatAnswerResult safeGuardBlockedAnswer(String advisorMessage, String meetingId) {
        ApiRequestLogSupport.info(log,
                "Q&A response blocked by SafeGuardAdvisor | meeting_id={}", meetingId);
        String answer = advisorMessage == null || advisorMessage.isBlank()
                ? SathiConstants.SAFE_GUARD_BLOCK_MESSAGE
                : advisorMessage.trim();
        return new ChatAnswerResult(answer, meetingId, List.of());
    }

    /** Formats retrieved documents as traceable context for model generation. */
    private static String toContext(List<Document> documents) {
        return documents.stream()
                .map(TranscriptDocumentSupport::toTraceableContext)
                .collect(Collectors.joining("\n\n"));
    }

    /** Rewrites follow-up context through the dedicated chat-client support component. */
    private List<String> expandFollowUpSearchQueries(String question, String conversationId) {
        return qnaChatClientSupport.expandFollowUpSearchQueries(question, conversationId);
    }

    /** Delegates grounded generation while keeping evidence selection and validation in this service. */
    private GroundedChatAnswer generateGroundedAnswer(
            String question,
            String context,
            List<Document> evidenceDocuments,
            boolean quoteCorrection,
            String conversationId) {
        return qnaChatClientSupport.generateGroundedAnswer(
                question, context, evidenceDocuments, quoteCorrection, conversationId);
    }

    /** Validates and scopes a supplied conversation ID so memory cannot cross meeting scopes. */
    private static String normalizeConversationId(String conversationId, String meetingId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String normalized = conversationId.trim();
        if (!CONVERSATION_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "conversation_id must be 1-128 characters and contain only letters, digits, '.', '_', ':', or '-'");
        }
        String scope = meetingId == null ? "all-meetings" : meetingId;
        return scope + "::" + normalized;
    }

    /** Requests bounded equivalent search queries from the chat-client support component. */
    private List<String> expandSearchQueries(String question) {
        return qnaChatClientSupport.expandSearchQueries(question, includeContentInLogs);
    }

    /** Runs filtered vector searches for each query and aggregates the matching documents. */
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
