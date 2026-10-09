package org.stg.savan.savanserver.model;

import java.util.List;

/** API response for retrieved transcript evidence returned to the Raspberry Pi. */
public record RetrievalAnswerResponse(String answer, String intent, List<RetrievalSource> sources) {
}
