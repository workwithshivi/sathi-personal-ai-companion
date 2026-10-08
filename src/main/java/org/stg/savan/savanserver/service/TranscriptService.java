package org.stg.savan.savanserver.service;

import org.stg.savan.savanserver.model.RetrievalResult;

import java.io.IOException;

public interface TranscriptService {

    String saveTranscript(String text, String device) throws IOException;

    String answerQuestion(String question);

    RetrievalResult retrieve(String question, String meetingId);
}
