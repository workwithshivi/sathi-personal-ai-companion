package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Internal structured response used to verify that generated answers quote retrieved evidence. */
public record GroundedChatAnswer(
        String answer,
        @JsonProperty("evidence_quotes") List<String> evidenceQuotes,
        @JsonProperty("supported_by_evidence") Boolean supportedByEvidence,
        @JsonProperty("corpus_wide_request") Boolean corpusWideRequest,
        @JsonProperty("blocked_by_safeguard") Boolean blockedBySafeguard
) {
    public GroundedChatAnswer(String answer, List<String> evidenceQuotes, Boolean supportedByEvidence) {
        this(answer, evidenceQuotes, supportedByEvidence, false, false);
    }

    public GroundedChatAnswer(
            String answer,
            List<String> evidenceQuotes,
            Boolean supportedByEvidence,
            Boolean corpusWideRequest) {
        this(answer, evidenceQuotes, supportedByEvidence, corpusWideRequest, false);
    }
}
