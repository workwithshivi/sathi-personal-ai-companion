package org.stg.savan.savanserver.config;

import org.stg.savan.savanserver.constants.SathiConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.defaultOptions(OllamaChatOptions.builder()
                        .temperature(SathiConstants.CHAT_TEMPERATURE)
                        .numPredict(SathiConstants.CHAT_MAX_PREDICT_TOKENS))
                .build();
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
