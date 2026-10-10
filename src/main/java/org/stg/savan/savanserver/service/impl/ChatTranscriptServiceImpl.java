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

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatTranscriptServiceImpl implements ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptServiceImpl.class);
    private static final Pattern DEFINITION_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:what\\s+is|what's|define|explain)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*\\s*$");
    private static final Pattern DESCRIPTIVE_TERM = Pattern.compile(
            "(?iu)\\b(?:assistant|application|system|project|tool|platform|service|product|prototype|software)\\b");

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
        for (int index = 0; index < chunks.size(); index++) {
            log.info("Transcript storage chunk {}/{} | meeting_id={} | content=\n{}",
                    index + 1, chunks.size(), meetingId, chunks.get(index).getText());
        }

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

        for (int index = 0; index < contextDocuments.size(); index++) {
            Document document = contextDocuments.get(index);
            log.info("Retrieved Q&A context chunk {}/{} | scope={} | metadata={} | content=\n{}",
                    index + 1, contextDocuments.size(), scope, document.getMetadata(), document.getText());
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

        var definitionAnswer = answerDefinitionFromEvidence(normalizedQuestion, contextDocuments);
        if (definitionAnswer.isPresent()) {
            log.info("Answered entity definition directly from transcript evidence | scope={} | answer=\n{}",
                    scope, definitionAnswer.get());
            return new ChatAnswerResult(definitionAnswer.get(), normalizedMeetingId, sources);
        }

        var deterministicAnswer = EvidenceCalculation.calculateResponseTimeReduction(
                normalizedQuestion, contextDocuments.stream().map(Document::getText).toList());
        if (deterministicAnswer.isPresent()) {
            log.info("Calculated response-time reduction from retrieved transcript evidence | scope={} | sources={}",
                    normalizedMeetingId == null ? "all_chat_meetings" : normalizedMeetingId,
                    sources.size());
            log.info("Exact deterministic /ai/qna answer | scope={} | answer=\n{}",
                    scope, deterministicAnswer.get());
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
        log.info("Exact generated /ai/qna answer | scope={} | answer=\n{}", scope, answer.trim());
        return new ChatAnswerResult(answer.trim(), normalizedMeetingId, sources);
    }

    private static java.util.Optional<String> answerDefinitionFromEvidence(
            String question, List<Document> documents) {
        var matcher = DEFINITION_QUESTION.matcher(question);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        String entity = matcher.group(1).replaceAll("[?.!]+$", "").trim();
        String normalizedEntity = normalizeForMatching(entity);
        if (normalizedEntity.isBlank()) {
            return java.util.Optional.empty();
        }

        for (Document document : documents) {
            String[] sentences = document.getText().split("(?<=[.!?])\\s+|\\R+");
            for (int index = 0; index < sentences.length; index++) {
                String sentence = sentences[index].trim();
                if (normalizeForMatching(sentence).contains(normalizedEntity)
                        && DESCRIPTIVE_TERM.matcher(sentence).find()) {
                    String answer = sentence;
                    if (index + 1 < sentences.length && !sentences[index + 1].isBlank()) {
                        answer += " " + sentences[index + 1].trim();
                    }
                    return java.util.Optional.of(answer);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static String normalizeForMatching(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT);
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
