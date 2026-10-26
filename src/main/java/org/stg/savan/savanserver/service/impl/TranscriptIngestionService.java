package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.MemoryType;
import org.stg.savan.savanserver.service.support.MeetingMemoryRepository;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.stg.savan.savanserver.util.MeetingIds;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns transcript normalization, chunk creation, vector persistence, and summary refresh. */
@Service
public class TranscriptIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptIngestionService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final MeetingMemoryRepository meetingMemoryRepository;
    private final MeetingSummaryGenerator meetingSummaryGenerator;

    /** Injects the storage, splitting, repository, and summary-generation collaborators. */
    public TranscriptIngestionService(
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            MeetingMemoryRepository meetingMemoryRepository,
            MeetingSummaryGenerator meetingSummaryGenerator) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.meetingMemoryRepository = meetingMemoryRepository;
        this.meetingSummaryGenerator = meetingSummaryGenerator;
    }

    /**
     * Normalizes the transcript and device, creates stable chunk IDs, stores transcript vectors,
     * then refreshes the meeting summary. A summary failure leaves the transcript chunks stored.
     */
    public String saveTranscript(String text, String device, String requestedMeetingId, boolean includeContentInLogs) {
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
                    ? List.of() : meetingMemoryRepository.findTranscriptChunks(meetingId);
            if (meetingChunks.isEmpty()) {
                meetingChunks = chunks;
            }
            vectorStore.add(List.of(meetingSummaryGenerator.create(meetingId, meetingChunks)));
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
}
