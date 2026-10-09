package org.stg.savan.savanserver.service;

import org.stg.savan.savanserver.model.ChatAnswerResult;

public interface ChatTranscriptService {

    String saveTranscript(String text, String device, String meetingId);

    ChatAnswerResult answerQuestion(String question, String meetingId);
}
