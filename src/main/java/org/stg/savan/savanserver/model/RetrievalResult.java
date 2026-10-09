package org.stg.savan.savanserver.model;

import java.util.List;

public record RetrievalResult(String context, String intent, List<RetrievalSource> sources) {
}
