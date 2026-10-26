package org.stg.savan.savanserver.service.impl;

import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.MeetingSummary;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Creates evidence-checked meeting summary documents from transcript chunks. */
@Service
public class MeetingSummaryGenerator {

    private final ChatClient chatClient;

    /** Provides the chat model used to summarize transcript evidence. */
    public MeetingSummaryGenerator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Generates a structured summary, verifies its quoted evidence against the source chunks,
     * and returns it as a vector-store document with meeting metadata.
     */
    public Document create(String meetingId, List<Document> transcriptChunks) {
        if (transcriptChunks == null || transcriptChunks.isEmpty()) {
            throw new IllegalArgumentException("Transcript chunks are required to create a meeting summary");
        }
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
}
