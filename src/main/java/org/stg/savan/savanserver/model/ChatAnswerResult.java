package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ChatAnswerResult(
        String answer,
        @JsonProperty("meeting_id") String meetingId,
        List<RetrievalSource> sources
) {
}
