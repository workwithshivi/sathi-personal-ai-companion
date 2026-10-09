package org.stg.savan.savanserver.service;

import org.stg.savan.savanserver.model.RetrievalResult;

public interface TranscriptService {

    String saveTranscript(String text, String device, String meetingId);

    RetrievalResult retrieve(String question, String meetingId);
}
