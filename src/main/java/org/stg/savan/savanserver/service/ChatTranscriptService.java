package org.stg.savan.savanserver.service;

import org.stg.savan.savanserver.model.ChatAnswerResult;

/** Defines the application operations exposed to the transcript and Q&A controllers. */
public interface ChatTranscriptService {

    /** Persists transcript text and returns the effective meeting identifier. */
    String saveTranscript(String text, String device, String meetingId);

    /** Answers a question within an optional meeting scope without conversation memory. */
    ChatAnswerResult answerQuestion(String question, String meetingId);

    /** Answers a question within an optional meeting scope and optional follow-up conversation. */
    ChatAnswerResult answerQuestion(String question, String meetingId, String conversationId);
}
