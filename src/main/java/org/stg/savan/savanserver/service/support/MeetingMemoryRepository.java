package org.stg.savan.savanserver.service.support;

import org.springframework.ai.document.Document;

import java.util.List;

/** Reads stored meeting chunks and cached meeting summaries for corpus-wide questions. */
public interface MeetingMemoryRepository {

    List<Document> findTranscriptChunks(String meetingId);

    List<Document> findAllTranscriptChunks();

    List<Document> findAllMeetingSummaries();
}
