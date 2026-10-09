package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptServiceImplTest {

    @Test
    void saveTranscriptAddsMeetingAndMemoryTypeMetadataToEveryChunk() {
        StubVectorStore vectorStore = new StubVectorStore();
        TranscriptServiceImpl service = service(vectorStore);

        String meetingId = service.saveTranscript(
                String.join(" ", Collections.nCopies(900, "transcript")), null, null);

        assertNotNull(meetingId);
        assertTrue(meetingId.matches("meeting-\\d{8}T\\d{6}Z-[0-9a-f]{8}"));
        assertFalse(vectorStore.addedDocuments.isEmpty());
        assertTrue(vectorStore.addedDocuments.stream().allMatch(document ->
                meetingId.equals(document.getMetadata().get("meeting_id"))
                        && "transcript".equals(document.getMetadata().get("memory_type"))
                        && "raspberry-pi".equals(document.getMetadata().get("device"))
                        && document.getMetadata().containsKey("received_at")));
    }

    @Test
    void saveTranscriptUsesProvidedMeetingIdForEveryChunk() {
        StubVectorStore vectorStore = new StubVectorStore();
        TranscriptServiceImpl service = service(vectorStore);
        String providedMeetingId = "pi-meeting-20261008-morning";

        String meetingId = service.saveTranscript(
                String.join(" ", Collections.nCopies(900, "transcript")),
                "raspberry-pi",
                providedMeetingId);

        assertEquals(providedMeetingId, meetingId);
        assertFalse(vectorStore.addedDocuments.isEmpty());
        assertTrue(vectorStore.addedDocuments.stream().allMatch(document ->
                providedMeetingId.equals(document.getMetadata().get("meeting_id"))));
    }

    @Test
    void actionItemParaphrasesExpandSearchAndMeetingIdScopesIt() {
        StubVectorStore vectorStore = new StubVectorStore();
        vectorStore.searchResults = List.of(document("Shivam will test transcript ingestion.",
                "meeting-123e4567-e89b-12d3-a456-426614174000", 1, "parent-1"));
        TranscriptServiceImpl service = service(vectorStore);

        var result = service.retrieve("What can we do next?",
                "meeting-123e4567-e89b-12d3-a456-426614174000");

        assertEquals("ACTION_ITEM", result.intent());
        assertTrue(vectorStore.searchRequests.get(1).getQuery().contains("follow-ups"));
        assertTrue(vectorStore.searchRequests.get(0).hasFilterExpression());
        assertTrue(vectorStore.searchRequests.get(1).hasFilterExpression());
        assertTrue(vectorStore.searchRequests.get(1).getFilterExpression().toString()
                .contains("meeting_id"));
        assertTrue(result.context().contains("chunk_index=1"));
        assertEquals(1, result.sources().size());
        assertEquals("meeting-123e4567-e89b-12d3-a456-426614174000",
                result.sources().get(0).meetingId());
    }

    @Test
    void duplicateChunksFromRepeatedUploadsAreCollapsed() {
        StubVectorStore vectorStore = new StubVectorStore();
        vectorStore.searchResults = List.of(
                document("Shivam will test transcript ingestion.",
                        "meeting-123e4567-e89b-12d3-a456-426614174000", 1, "parent-1"),
                document("Shivam will test transcript ingestion.",
                        "meeting-223e4567-e89b-12d3-a456-426614174000", 1, "parent-2"),
                document("The team will verify the physical mute switch.",
                        "meeting-123e4567-e89b-12d3-a456-426614174000", 2, "parent-1"));
        TranscriptServiceImpl service = service(vectorStore);

        var result = service.retrieve("What are the action items?", null);

        assertEquals(2, result.sources().size());
        assertEquals(2, result.context().split("\\[meeting_id=", -1).length - 1);
    }

    @Test
    void originalAndExpandedQueriesAreMergedWhenEitherFindsEvidence() {
        StubVectorStore vectorStore = new StubVectorStore();
        Document originalHit = document("The Pi team owns transcript ingestion.",
                "meeting-123e4567-e89b-12d3-a456-426614174000", 0, "parent-1", 0.58);
        Document expandedHit = document("Maya will verify similarity search.",
                "meeting-123e4567-e89b-12d3-a456-426614174000", 1, "parent-1", 0.66);
        vectorStore.searchResultProvider = request -> request.getQuery().contains("Find assigned tasks")
                ? List.of(expandedHit)
                : List.of(originalHit);
        TranscriptServiceImpl service = service(vectorStore);

        var result = service.retrieve("What is the to-do?",
                "meeting-123e4567-e89b-12d3-a456-426614174000");

        assertEquals(2, vectorStore.searchRequests.size());
        assertEquals(2, result.sources().size());
        assertTrue(result.context().contains("The Pi team owns transcript ingestion."));
        assertTrue(result.context().contains("Maya will verify similarity search."));
        assertEquals(0.66, result.sources().get(0).score());
    }

    private static TranscriptServiceImpl service(StubVectorStore vectorStore) {
        return new TranscriptServiceImpl(vectorStore,
                TokenTextSplitter.builder()
                        .withChunkSize(350)
                        .withMinChunkSizeChars(300)
                        .withMinChunkLengthToEmbed(20)
                        .withMaxNumChunks(5000)
                        .withKeepSeparator(true)
                        .build());
    }

    private static Document document(String text, String meetingId, int chunkIndex,
                                     String parentDocumentId) {
        return document(text, meetingId, chunkIndex, parentDocumentId, 0.7);
    }

    private static Document document(String text, String meetingId, int chunkIndex,
                                     String parentDocumentId, double score) {
        return Document.builder()
                .text(text)
                .metadata(Map.of(
                        "meeting_id", meetingId,
                        "chunk_index", chunkIndex,
                        "parent_document_id", parentDocumentId))
                .score(score)
                .build();
    }

    private static final class StubVectorStore implements VectorStore {
        private final List<Document> addedDocuments = new ArrayList<>();
        private final List<SearchRequest> searchRequests = new ArrayList<>();
        private List<Document> searchResults = List.of();
        private Function<SearchRequest, List<Document>> searchResultProvider = request -> searchResults;

        @Override
        public void add(List<Document> documents) {
            addedDocuments.addAll(documents);
        }

        @Override
        public void delete(List<String> ids) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            searchRequests.add(request);
            return searchResultProvider.apply(request);
        }
    }
}
