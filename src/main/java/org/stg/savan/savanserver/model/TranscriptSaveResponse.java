package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TranscriptSaveResponse(
        String status,
        @JsonProperty("meeting_id") String meetingId
) {
}
