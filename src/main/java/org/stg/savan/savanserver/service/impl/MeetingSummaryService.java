package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.service.support.MeetingMemoryRepository;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads corpus meeting summaries and backfills missing summaries from stored transcript chunks. */
@Service
public class MeetingSummaryService {

    private static final Logger log = LoggerFactory.getLogger(MeetingSummaryService.class);

    private final MeetingMemoryRepository meetingMemoryRepository;
    private final VectorStore vectorStore;
    private final MeetingSummaryGenerator meetingSummaryGenerator;

    /** Injects the meeting-memory reader, vector writer, and summary generator. */
    public MeetingSummaryService(
            MeetingMemoryRepository meetingMemoryRepository,
            VectorStore vectorStore,
            MeetingSummaryGenerator meetingSummaryGenerator) {
        this.meetingMemoryRepository = meetingMemoryRepository;
        this.vectorStore = vectorStore;
        this.meetingSummaryGenerator = meetingSummaryGenerator;
    }

    /**
     * Returns one summary per meeting. Existing summaries are reused; missing ones are generated
     * from transcript chunks and persisted. If any required backfill fails, returns no summaries.
     */
    public List<Document> loadAllOrBackfillMissing() {
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
                    Document summary = meetingSummaryGenerator.create(entry.getKey(), entry.getValue());
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
}
