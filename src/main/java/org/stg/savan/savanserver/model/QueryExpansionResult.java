package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Structured result containing generic search-query rewrites for a user question. */
public record QueryExpansionResult(
        @JsonProperty("queries") List<String> queries
) {
}
