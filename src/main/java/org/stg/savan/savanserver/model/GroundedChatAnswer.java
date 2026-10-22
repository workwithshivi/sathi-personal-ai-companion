package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Internal structured response used to verify that generated answers quote retrieved evidence. */
public record GroundedChatAnswer(
        String answer,
        @JsonProperty("evidence_quotes") List<String> evidenceQuotes,
        @JsonProperty("supported_by_evidence") boolean supportedByEvidence
) {
}
