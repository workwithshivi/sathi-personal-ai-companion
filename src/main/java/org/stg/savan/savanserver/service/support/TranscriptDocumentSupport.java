package org.stg.savan.savanserver.service.support;

import org.springframework.ai.document.Document;
import org.stg.savan.savanserver.model.RetrievalSource;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared transformations for transcript chunks returned by the vector store. */
public final class TranscriptDocumentSupport {

    private TranscriptDocumentSupport() {
    }

    public static List<Document> deduplicateAndLimit(List<Document> documents, int limit) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, Document> bestByContent = new LinkedHashMap<>();
        for (Document document : documents) {
            String normalizedContent = normalizeText(document.getText());
            Document current = bestByContent.get(normalizedContent);
            if (current == null || scoreOf(document) > scoreOf(current)) {
                bestByContent.put(normalizedContent, document);
            }
        }
        return bestByContent.values().stream()
                .sorted(Comparator.comparing(TranscriptDocumentSupport::scoreOf).reversed())
                .limit(limit)
                .toList();
    }

    public static RetrievalSource toSource(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievalSource(
                (String) metadata.get("meeting_id"),
                asInteger(metadata.get("chunk_index")),
                (String) metadata.get("parent_document_id"),
                document.getScore());
    }

    public static String toTraceableContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return "[meeting_id=" + metadata.get("meeting_id")
                + ", chunk_index=" + metadata.get("chunk_index")
                + ", parent_document_id=" + metadata.get("parent_document_id") + "]\n"
                + document.getText();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private static double scoreOf(Document document) {
        return document.getScore() == null ? Double.NEGATIVE_INFINITY : document.getScore();
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
