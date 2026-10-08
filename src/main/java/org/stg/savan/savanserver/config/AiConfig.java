package org.stg.savan.savanserver.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(350)
                .withMinChunkSizeChars(300)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(5000)
                .withKeepSeparator(true)
                .build();
    }
}