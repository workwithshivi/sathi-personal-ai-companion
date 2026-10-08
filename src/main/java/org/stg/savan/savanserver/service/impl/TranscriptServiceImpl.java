package org.stg.savan.savanserver.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.service.TranscriptService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
public class TranscriptServiceImpl implements TranscriptService {
    private static final Logger log =
            LoggerFactory.getLogger(TranscriptServiceImpl.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public TranscriptServiceImpl(VectorStore vectorStore, TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    @Override
    public void saveTranscript(String text, String device) throws IOException {

        String normalizedText = text.trim();

        String normalizedDevice = device == null || device.isBlank()
                ? "raspberry-pi"
                : device;

        String receivedAt = Instant.now().toString();
        String meetingId = "meeting-" + UUID.randomUUID();

        log.info("Saving transcript from device: {}", normalizedDevice);
        log.debug("Transcript content: {}", normalizedText);

        Document document = new Document(
                normalizedText,
                Map.of(
                        "received_at", receivedAt,
                        "device", normalizedDevice,
                        "memory_type", MemoryType.TRANSCRIPT.getMetadataValue(),
                        "meeting_id", meetingId
                )
        );

        List<Document> chunks = textSplitter.apply(List.of(document));

        log.info("Transcript split into {} chunks", chunks.size());

        vectorStore.add(chunks);

        log.info("Transcript successfully stored in vector store");
    }

    @Override
    public String answerQuestion(String question) {

        log.info("Processing Q&A request");
        log.debug("Question: {}", question);

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question.trim())
                        .topK(3)
                        .similarityThreshold(0.55)
                        .build()
        );

        if (documents == null || documents.isEmpty()) {
            log.info("No relevant memories found for question");

            return "I could not find anything relevant in the meeting transcript.";
        }

        log.info("Retrieved {} relevant memories", documents.size());

        documents.forEach(document ->
                log.info(
                        "Retrieved memory | Score: {} | Metadata: {} | Content: {}",
                        document.getScore(),
                        document.getMetadata(),
                        document.getText())

        );

        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
    }
}
