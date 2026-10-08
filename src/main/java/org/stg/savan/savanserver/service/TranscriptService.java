package org.stg.savan.savanserver.service;

import java.io.IOException;

public interface TranscriptService {

    void saveTranscript(String text, String device) throws IOException;

    String answerQuestion(String question);
}