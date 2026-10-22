package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.GroundedChatAnswer;
import org.stg.savan.savanserver.model.GeneralChatAnswer;
import org.stg.savan.savanserver.model.MeetingSummary;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.model.QueryExpansionResult;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.stg.savan.savanserver.service.support.MeetingMemoryRepository;
import org.stg.savan.savanserver.util.MeetingIds;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatTranscriptServiceImpl implements ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptServiceImpl.class);
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final ChatClient chatClient;
    private final MeetingMemoryRepository meetingMemoryRepository;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final ChatMemory chatMemory;
    private final SafeGuardAdvisor safeGuardAdvisor;
    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

    @Value("${sathi.logging.include-content:false}")
    private boolean includeContentInLogs;

    @Autowired
    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient,
            MeetingMemoryRepository meetingMemoryRepository,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            ChatMemory chatMemory,
            @Qualifier("safeGuardAdvisor")
            SafeGuardAdvisor safeGuardAdvisor,
            RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.chatClient = chatClient;
        this.meetingMemoryRepository = meetingMemoryRepository;
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.chatMemory = chatMemory;
        this.safeGuardAdvisor = safeGuardAdvisor;
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
    }

    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient,
            MeetingMemoryRepository meetingMemoryRepository) {
        this(vectorStore, textSplitter, chatClient, meetingMemoryRepository, null, null, null, null);
    }

    public ChatTranscriptServiceImpl(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            ChatClient chatClient) {
        this(vectorStore, textSplitter, chatClient, null);
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
        try {
            vectorStore.delete(List.of(QnaEvidenceSupport.stableChunkId(meetingId, -1, "meeting-summary")));
            List<Document> meetingChunks = meetingMemoryRepository == null
                    ? chunks : meetingMemoryRepository.findTranscriptChunks(meetingId);
            if (meetingChunks.isEmpty()) {
                meetingChunks = chunks;
            }
            vectorStore.add(List.of(createMeetingSummary(meetingId, meetingChunks)));
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "Meeting summary refresh failed; transcript chunks remain stored | meeting_id={} | exception={}",
                    meetingId, exception.getClass().getSimpleName());
        }
        ApiRequestLogSupport.info(log,
                "Stored ChatClient transcript | meeting_id={} | chunks={} | device={} | elapsed_ms={}",
                meetingId, chunks.size(), normalizedDevice, ApiRequestLogSupport.elapsedMillis(startedAt));
        return meetingId;
    }

    @Override
    public ChatAnswerResult answerQuestion(String question, String meetingId) {
        return answerQuestion(question, meetingId, null);
    }

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
        if (scopedConversationId != null && chatMemory != null) {
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

        if (plannedQuery.intent().equals("ACTION_ITEM")) {
            var actionAnswer = QnaEvidenceSupport.answerActionFromEvidence(normalizedQuestion, contextDocuments);
            if (actionAnswer.isPresent()) {
                List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                        List.of(actionAnswer.get()), contextDocuments);
                ApiRequestLogSupport.info(log,
                        "Answered action question from matching transcript sentences | scope={}", scope);
                return new ChatAnswerResult(actionAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
            }
            ApiRequestLogSupport.info(log,
                    "No directly matching action sentence found; abstaining | scope={}", scope);
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }

        if (plannedQuery.intent().equals("DECISION")) {
            var decisionAnswer = QnaEvidenceSupport.answerDecisionFromEvidence(normalizedQuestion, contextDocuments);
            if (decisionAnswer.isPresent()) {
                List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                        List.of(decisionAnswer.get()), contextDocuments);
                ApiRequestLogSupport.info(log,
                        "Answered decision question from matching transcript sentences | scope={}", scope);
                return new ChatAnswerResult(decisionAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
            }
            ApiRequestLogSupport.info(log,
                    "No directly matching decision sentence found; abstaining | scope={}", scope);
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }

        if (plannedQuery.intent().equals("ISSUE")) {
            var issueAnswer = QnaEvidenceSupport.answerIssueFromEvidence(normalizedQuestion, contextDocuments);
            if (issueAnswer.isPresent()) {
                List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                        List.of(issueAnswer.get()), contextDocuments);
                ApiRequestLogSupport.info(log,
                        "Answered issue question directly from matching transcript sentence | scope={}", scope);
                return new ChatAnswerResult(issueAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
            }
        }

        if (plannedQuery.intent().equals("YES_NO")) {
            var yesNoAnswer = QnaEvidenceSupport.answerYesNoFromEvidence(normalizedQuestion, contextDocuments);
            if (yesNoAnswer.isPresent()) {
                List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                        List.of(yesNoAnswer.get()), contextDocuments);
                ApiRequestLogSupport.info(log,
                        "Answered yes/no question directly from matching transcript sentence | scope={}", scope);
                return new ChatAnswerResult(yesNoAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
            }
            ApiRequestLogSupport.info(log,
                    "No single sentence directly answers yes/no question; abstaining | scope={}", scope);
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }

        if (plannedQuery.intent().equals("RELATION")) {
            var relationAnswer = QnaEvidenceSupport.answerRelationFromEvidence(requiredSubject, contextDocuments);
            if (relationAnswer.isPresent()) {
                List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                        List.of(relationAnswer.get()), contextDocuments);
                ApiRequestLogSupport.info(log,
                        "Answered relation question from an explicit matching sentence | scope={}", scope);
                return new ChatAnswerResult(relationAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
            }
            ApiRequestLogSupport.info(log,
                    "No explicit relation sentence found; abstaining | scope={} | subject={}", scope, requiredSubject);
            return answerWithoutTranscript(normalizedQuestion, normalizedMeetingId);
        }

        var definitionAnswer = plannedQuery.intent().equals("DEFINITION")
                ? QnaEvidenceSupport.answerDefinitionFromEvidence(normalizedQuestion, contextDocuments)
                : java.util.Optional.<String>empty();
        if (definitionAnswer.isPresent()) {
            List<Document> supportingDocuments = QnaEvidenceSupport.documentsContainingEvidence(
                    List.of(definitionAnswer.get()), contextDocuments);
            ApiRequestLogSupport.info(log,
                    "Answered entity definition directly from transcript evidence | scope={} | answer_characters={}",
                    scope, definitionAnswer.get().length());
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Exact deterministic /ai/qna answer | scope={} | answer=\n{}", scope, definitionAnswer.get());
            return new ChatAnswerResult(definitionAnswer.get(), normalizedMeetingId, toSources(supportingDocuments));
        }

        var calculation = EvidenceCalculation.calculatePercentageChangeWithEvidence(
                normalizedQuestion, contextDocuments.stream().map(Document::getText).toList());
        if (calculation.isPresent()) {
            EvidenceCalculation.CalculationResult result = calculation.get();
            List<Document> supportingDocuments = contextDocuments.stream()
                    .filter(document -> document.getText().equals(result.evidence()))
                    .toList();
            ApiRequestLogSupport.info(log,
                    "Calculated percentage change from retrieved transcript evidence | scope={} | sources={}",
                    scope, supportingDocuments.size());
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Exact deterministic /ai/qna answer | scope={} | answer=\n{}", scope, result.answer());
            return new ChatAnswerResult(result.answer(), normalizedMeetingId, toSources(supportingDocuments));
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
            List<Document> meetingSummaries = loadMeetingSummaries();
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

    private static List<org.stg.savan.savanserver.model.RetrievalSource> toSources(List<Document> documents) {
        return documents.stream().map(TranscriptDocumentSupport::toSource).toList();
    }

    private ChatAnswerResult answerWithoutTranscript(String question, String meetingId) {
        if (meetingId != null) {
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, meetingId, List.of());
        }
        try {
            var prompt = chatClient.prompt()
                    .system(SathiConstants.GENERAL_RESPONSE_SYSTEM_PROMPT)
                    .user(question);
            GeneralChatAnswer generated = prompt.call()
                    .entity(GeneralChatAnswer.class, spec -> spec
                            .useProviderStructuredOutput()
                            .validateSchema());
            if (generated == null || generated.answer() == null || generated.answer().isBlank()) {
                ApiRequestLogSupport.info(log,
                        "General response generation returned no answer | scope=all_chat_meetings");
                return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, null, List.of());
            }
            String answer = SathiConstants.GENERAL_RESPONSE_PREFIX + generated.answer().trim();
            ApiRequestLogSupport.info(log,
                    "Generated general ChatClient response after transcript abstention | sources=0 | answer_characters={}",
                    answer.length());
            return new ChatAnswerResult(answer, null, List.of());
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "General ChatClient fallback failed; returning transcript abstention | exception={}",
                    exception.getClass().getSimpleName());
            return new ChatAnswerResult(SathiConstants.CHAT_NOT_FOUND_MESSAGE, null, List.of());
        }
    }

    private boolean containsConfiguredSensitiveWord(String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return SathiConstants.SAFE_GUARD_SENSITIVE_WORDS.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedQuestion::contains);
    }

    private ChatAnswerResult safeGuardBlockedAnswer(String advisorMessage, String meetingId) {
        ApiRequestLogSupport.info(log,
                "Q&A response blocked by SafeGuardAdvisor | meeting_id={}", meetingId);
        String answer = advisorMessage == null || advisorMessage.isBlank()
                ? SathiConstants.SAFE_GUARD_BLOCK_MESSAGE
                : advisorMessage.trim();
        return new ChatAnswerResult(answer, meetingId, List.of());
    }

    private static String toContext(List<Document> documents) {
        return documents.stream()
                .map(TranscriptDocumentSupport::toTraceableContext)
                .collect(Collectors.joining("\n\n"));
    }

    private Document createMeetingSummary(String meetingId, List<Document> transcriptChunks) {
        String transcript = transcriptChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
        MeetingSummary summary = chatClient.prompt()
                .system(SathiConstants.MEETING_SUMMARY_SYSTEM_PROMPT)
                .user(user -> user.text("Meeting ID: {meetingId}\nTranscript excerpts:\n{transcript}")
                        .param("meetingId", meetingId)
                        .param("transcript", transcript))
                .call()
                .entity(MeetingSummary.class, spec -> spec
                        .useProviderStructuredOutput()
                        .validateSchema());
        if (summary == null || !Boolean.TRUE.equals(summary.supportedByEvidence())
                || summary.summary() == null || summary.summary().isBlank()
                || !QnaEvidenceSupport.checkEvidenceQuotes(summary.evidenceQuotes(), transcriptChunks).valid()) {
            throw new IllegalStateException("Meeting summary did not pass transcript evidence validation");
        }

        String content = "Meeting summary:\n" + summary.summary()
                + "\nVerbatim transcript evidence:\n"
                + summary.evidenceQuotes().stream().map(quote -> "- " + quote)
                .collect(Collectors.joining("\n"));
        Map<String, Object> metadata = new HashMap<>(transcriptChunks.getFirst().getMetadata());
        metadata.put("meeting_id", meetingId);
        metadata.put("pipeline", SathiConstants.CHAT_PIPELINE);
        metadata.put("memory_type", MemoryType.MEETING_SUMMARY.getMetadataValue());
        metadata.put("chunk_index", -1);
        metadata.putIfAbsent("parent_document_id", "meeting-summary-" + meetingId);
        return Document.builder()
                .id(QnaEvidenceSupport.stableChunkId(meetingId, -1, "meeting-summary"))
                .text(content)
                .metadata(metadata)
                .build();
    }

    private List<String> expandFollowUpSearchQueries(String question, String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        if (messages.isEmpty()) {
            return List.of();
        }
        String fullHistory = messages.stream()
                .map(message -> message.getMessageType() + ": " + message.getText())
                .collect(Collectors.joining("\n\n"));
        int historyLimit = 6000;
        String history = fullHistory.length() > historyLimit
                ? fullHistory.substring(fullHistory.length() - historyLimit)
                : fullHistory;
        try {
            QueryExpansionResult expansion = chatClient.prompt()
                    .system(SathiConstants.FOLLOW_UP_QUERY_SYSTEM_PROMPT)
                    .user(user -> user.text("""
                            Prior conversation:
                            {history}

                            Current follow-up question:
                            {question}
                            """)
                            .param("history", history)
                            .param("question", question))
                    .call()
                    .entity(QueryExpansionResult.class, spec -> spec
                            .useProviderStructuredOutput()
                            .validateSchema());
            if (expansion == null || expansion.queries() == null) {
                return List.of();
            }
            return expansion.queries().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(query -> !query.isBlank() && query.length() <= 500
                            && !query.equalsIgnoreCase(question))
                    .distinct()
                    .limit(SathiConstants.QUERY_EXPANSION_MAX_VARIANTS)
                    .toList();
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "Conversation-aware query rewrite unavailable; continuing with the current question | exception={}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<Document> loadMeetingSummaries() {
        if (meetingMemoryRepository == null) {
            return List.of();
        }
        List<Document> transcriptChunks = meetingMemoryRepository.findAllTranscriptChunks();
        Map<String, List<Document>> chunksByMeeting = transcriptChunks.stream()
                .filter(document -> document.getMetadata().get("meeting_id") != null)
                .collect(Collectors.groupingBy(
                        document -> document.getMetadata().get("meeting_id").toString(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, Document> summariesByMeeting = meetingMemoryRepository.findAllMeetingSummaries().stream()
                .filter(document -> document.getMetadata().get("meeting_id") != null)
                .collect(Collectors.toMap(
                        document -> document.getMetadata().get("meeting_id").toString(),
                        document -> document,
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        List<Document> missingSummaries = new ArrayList<>();
        for (Map.Entry<String, List<Document>> entry : chunksByMeeting.entrySet()) {
            if (!summariesByMeeting.containsKey(entry.getKey())) {
                try {
                    Document summary = createMeetingSummary(entry.getKey(), entry.getValue());
                    missingSummaries.add(summary);
                    summariesByMeeting.put(entry.getKey(), summary);
                } catch (RuntimeException exception) {
                    ApiRequestLogSupport.info(log,
                            "Could not backfill meeting summary; corpus-wide answer will abstain | meeting_id={} | exception={}",
                            entry.getKey(), exception.getClass().getSimpleName());
                    return List.of();
                }
            }
        }
        if (!missingSummaries.isEmpty()) {
            vectorStore.add(missingSummaries);
            ApiRequestLogSupport.info(log, "Backfilled missing meeting summaries | meetings={}",
                    missingSummaries.size());
        }
        return chunksByMeeting.keySet().stream().map(summariesByMeeting::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private GroundedChatAnswer generateGroundedAnswer(
            String question,
            String context,
            List<Document> evidenceDocuments,
            boolean quoteCorrection,
            String conversationId) {
        String systemPrompt = SathiConstants.CHAT_SYSTEM_PROMPT;
        if (conversationId != null) {
            systemPrompt += """


                    Use earlier conversation messages only to resolve references in the current question.
                    Earlier answers are not evidence. Support all facts in this answer with the supplied
                    transcript evidence and exact evidence quotes.
                    """;
        }
        if (quoteCorrection) {
            systemPrompt += """

                    Your previous citation failed validation. Copy each evidence quote exactly, character-for-character,
                    from one contiguous sentence in the supplied transcript excerpts. Do not paraphrase a quote.
                    If you cannot find an exact supporting sentence, return supported_by_evidence=false and no quotes.
                    """;
        }
        var prompt = chatClient.prompt().system(systemPrompt);
        if (retrievalAugmentationAdvisor != null) {
            prompt.user(question)
                    .advisors(retrievalAugmentationAdvisor)
                    .advisors(advisor -> advisor.param(
                            SathiConstants.RAG_DOCUMENTS_CONTEXT_KEY, evidenceDocuments));
        } else {
            prompt.user(user -> user.text("""
                            Question:
                            {question}

                            Retrieved transcript evidence:
                            {context}
                            """)
                    .param("question", question)
                    .param("context", context));
        }
        if (safeGuardAdvisor != null) {
            prompt.advisors(safeGuardAdvisor);
        }
        if (conversationId != null && messageChatMemoryAdvisor != null) {
            prompt.advisors(messageChatMemoryAdvisor)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
        }
        return prompt.call()
                .entity(GroundedChatAnswer.class, spec -> spec
                        .useProviderStructuredOutput()
                        .validateSchema());
    }

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

    private List<String> expandSearchQueries(String question) {
        long startedAt = System.nanoTime();
        try {
            QueryExpansionResult expansion = chatClient.prompt()
                    .system(SathiConstants.QUERY_EXPANSION_SYSTEM_PROMPT)
                    .user(user -> user.text("Create equivalent search queries for this question:\n{question}")
                            .param("question", question))
                    .call()
                    .entity(QueryExpansionResult.class, spec -> spec
                            .useProviderStructuredOutput()
                            .validateSchema());
            if (expansion == null || expansion.queries() == null) {
                return List.of();
            }

            List<String> queries = expansion.queries().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(query -> !query.isBlank() && query.length() <= 500
                            && !query.equalsIgnoreCase(question))
                    .distinct()
                    .limit(SathiConstants.QUERY_EXPANSION_MAX_VARIANTS)
                    .toList();
            ApiRequestLogSupport.debug(log,
                    "Generic query rewrites generated | variants={} | elapsed_ms={}",
                    queries.size(), ApiRequestLogSupport.elapsedMillis(startedAt));
            for (int index = 0; index < queries.size(); index++) {
                ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                        "Generic search query rewrite | variant={}/{} | query={}",
                        index + 1, queries.size(), queries.get(index));
            }
            return queries;
        } catch (RuntimeException e) {
            ApiRequestLogSupport.info(log,
                    "Generic query expansion unavailable; continuing with existing queries | exception={}",
                    e.getClass().getSimpleName());
            return List.of();
        }
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
