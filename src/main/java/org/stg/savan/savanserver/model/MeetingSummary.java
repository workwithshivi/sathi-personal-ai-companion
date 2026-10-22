package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Grounded compact summary plus verbatim transcript excerpts used to verify and trace it. */
public record MeetingSummary(
        String summary,
        @JsonProperty("evidence_quotes") List<String> evidenceQuotes,
        @JsonProperty("supported_by_evidence") Boolean supportedByEvidence
) {
}
