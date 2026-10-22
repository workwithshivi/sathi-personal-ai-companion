package org.stg.savan.savanserver.config;

import org.stg.savan.savanserver.constants.SathiConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.stg.savan.savanserver.constants.SathiConstants.RAG_DOCUMENTS_CONTEXT_KEY;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                //.defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultOptions(OllamaChatOptions.builder()
                        .temperature(SathiConstants.CHAT_TEMPERATURE)
                        .numPredict(SathiConstants.CHAT_MAX_PREDICT_TOKENS))
                .build();
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public SafeGuardAdvisor safeGuardAdvisor() {
        return safeGuardAdvisor(
                """
                        {"answer":"I cannot answer because the request or its supporting text matches a locally configured restricted term.","evidence_quotes":[],"supported_by_evidence":false,"corpus_wide_request":false,"blocked_by_safeguard":true}
                        """);
    }

    private static SafeGuardAdvisor safeGuardAdvisor(String failureResponse) {
        return SafeGuardAdvisor.builder()
                .sensitiveWords(SathiConstants.SAFE_GUARD_SENSITIVE_WORDS)
                .failureResponse(failureResponse)
                .build();
    }

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor() {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(query -> evidenceDocuments(query))
                .build();
    }

    private static List<Document> evidenceDocuments(Query query) {
        Object documents = query.context().get(RAG_DOCUMENTS_CONTEXT_KEY);
        if (!(documents instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .toList();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(SathiConstants.TRANSCRIPT_CHUNK_SIZE)
                .withMinChunkSizeChars(SathiConstants.TRANSCRIPT_MIN_CHUNK_SIZE_CHARS)
                .withMinChunkLengthToEmbed(SathiConstants.TRANSCRIPT_MIN_CHUNK_LENGTH_TO_EMBED)
                .withMaxNumChunks(SathiConstants.TRANSCRIPT_MAX_CHUNKS)
                .withKeepSeparator(true)
                .build();
    }
}
