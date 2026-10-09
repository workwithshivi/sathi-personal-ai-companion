package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

/** Request body shared by the transcript ingestion endpoints. */
public record TranscriptRequest(
        List<String> text,
        String device,
        @JsonProperty("meeting_id") String meetingId
) {

    /** Combines non-blank transcript segments in their received order for normal chunking. */
    public String combinedText() {
        if (text == null) {
            return "";
        }
        return text.stream()
                .filter(segment -> segment != null && !segment.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("\n"));
    }
}
