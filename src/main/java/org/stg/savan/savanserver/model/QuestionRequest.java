package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body shared by the question-answering endpoints. */
public record QuestionRequest(
        String question,
        @JsonProperty("meeting_id") String meetingId
) {
}
