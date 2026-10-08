package org.stg.savan.savanserver.service.impl;

import org.stg.savan.savanserver.service.TranscriptService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

@Service
public class TranscriptServiceImpl implements TranscriptService {

    private static final Path OUTPUT_FILE = Path.of("transcripts.jsonl");

    private final ObjectMapper objectMapper;

    public TranscriptServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveTranscript(String text, String device) throws IOException {

        Map<String, String> record = Map.of(
                "received_at", Instant.now().toString(),
                "text", text.trim(),
                "device", device == null || device.isBlank()
                        ? "raspberry-pi"
                        : device
        );

        String line = objectMapper.writeValueAsString(record)
                + System.lineSeparator();

        synchronized (TranscriptServiceImpl.class) {
            Files.writeString(
                    OUTPUT_FILE,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    @Override
    public String answerQuestion(String question) {

        // TODO:
        // Spring AI + pgvector semantic search will be implemented here.
        // This service should return relevant memory/context.

        return "Q&A processing is not implemented yet";
    }
}