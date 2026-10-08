package org.stg.savan.savanserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RetrievalSource(
        @JsonProperty("meeting_id") String meetingId,
        @JsonProperty("chunk_index") Integer chunkIndex,
        @JsonProperty("parent_document_id") String parentDocumentId,
        Double score
) {
}
