package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.GeneralChatAnswer;
import org.stg.savan.savanserver.model.GroundedChatAnswer;
import org.stg.savan.savanserver.model.QueryExpansionResult;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Encapsulates Q&A-specific chat prompts, structured model calls, and conversation-aware rewrites. */
@Service
public class QnaChatClientSupport {

    private static final Logger log = LoggerFactory.getLogger(QnaChatClientSupport.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final SafeGuardAdvisor safeGuardAdvisor;
    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

    /** Injects the model client and optional Spring AI advisors used by Q&A calls. */
    public QnaChatClientSupport(
            ChatClient chatClient,
            ChatMemory chatMemory,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            SafeGuardAdvisor safeGuardAdvisor,
            RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.safeGuardAdvisor = safeGuardAdvisor;
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
    }

    /** Rewrites a follow-up question into standalone retrieval queries using the scoped chat history. */
    public List<String> expandFollowUpSearchQueries(String question, String conversationId) {
        if (chatMemory == null) {
            return List.of();
        }
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
            return boundedQueries(expansion, question);
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "Conversation-aware query rewrite unavailable; continuing with the current question | exception={}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Creates a small set of equivalent query rewrites for sparse retrieval. */
    public List<String> expandSearchQueries(String question, boolean includeContentInLogs) {
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
            List<String> queries = boundedQueries(expansion, question);
            ApiRequestLogSupport.debug(log,
                    "Generic query rewrites generated | variants={} | elapsed_ms={}",
                    queries.size(), ApiRequestLogSupport.elapsedMillis(startedAt));
            for (int index = 0; index < queries.size(); index++) {
                ApiRequestLogSupport.debugWhen(log, includeContentInLogs,
                        "Generic search query rewrite | variant={}/{} | query={}",
                        index + 1, queries.size(), queries.get(index));
            }
            return queries;
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "Generic query expansion unavailable; continuing with existing queries | exception={}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Generates a schema-validated grounded answer using only the evidence supplied by the caller. */
    public GroundedChatAnswer generateGroundedAnswer(
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

    /** Generates a general response for an unscoped transcript miss, without treating it as transcript evidence. */
    public Optional<String> generateGeneralAnswer(String question) {
        try {
            GeneralChatAnswer generated = chatClient.prompt()
                    .system(SathiConstants.GENERAL_RESPONSE_SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .entity(GeneralChatAnswer.class, spec -> spec
                            .useProviderStructuredOutput()
                            .validateSchema());
            return generated == null || generated.answer() == null || generated.answer().isBlank()
                    ? Optional.empty() : Optional.of(generated.answer().trim());
        } catch (RuntimeException exception) {
            ApiRequestLogSupport.info(log,
                    "General ChatClient fallback failed | exception={}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Filters model-generated queries for blanks, duplicates, excessive length, and the original question. */
    private static List<String> boundedQueries(QueryExpansionResult expansion, String question) {
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
    }
}
