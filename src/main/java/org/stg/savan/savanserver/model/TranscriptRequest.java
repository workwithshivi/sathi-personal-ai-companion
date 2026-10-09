package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body shared by the transcript ingestion endpoints. */
public record TranscriptRequest(
        String text,
        String device,
        @JsonProperty("meeting_id") String meetingId
) {
}
