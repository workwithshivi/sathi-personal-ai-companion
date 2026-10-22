package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.stg.savan.savanserver.model.GroundedChatAnswer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;

class ChatTranscriptServiceImplTest {

    @Test
    void retriesExpandedIncidentImpactQueriesAtFallbackThresholdWithinMeetingScope() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mockChatClient("No, the staging incident did not affect production.",
                "The incident did not affect production!", true);
        Document evidence = Document.builder()
                .text("The incident did not affect production.")
                .metadata(Map.of(
                        "meeting_id", "sathi-office-demo-20261009",
                        "chunk_index", 2,
                        "parent_document_id", "parent-1"))
                .score(0.49)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.getSimilarityThreshold() < 0.5
                    && request.getQuery().contains("confirms or denies this relationship")) {
                return List.of(evidence);
            }
            return List.of();
        });
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion(
                "Did the outage affect production?", "sathi-office-demo-20261009");

        assertEquals("No, the staging incident did not affect production.", result.answer());
        assertEquals(1, result.sources().size());
        assertEquals("sathi-office-demo-20261009", result.sources().getFirst().meetingId());
        assertTrue(result.sources().getFirst().score() >= 0.45);
    }

    @Test
    void mergesFallbackEvidenceWhenPrimarySearchReturnsOnlyOneDistinctChunk() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mockChatClient("The team chose to keep both flows available.",
                "The team chose to keep both flows available.", true);
        Document primary = evidence("The team discussed the laptop flow.", 0, 0.61);
        Document fallback = evidence("The team chose to keep both flows available.", 1, 0.49);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.getSimilarityThreshold() < 0.5) {
                return List.of(fallback);
            }
            return List.of(primary);
        });
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion("What approach did the team choose?", null);

        assertEquals(2, result.sources().size());
        assertEquals("The team chose to keep both flows available.", result.answer());
    }

    @Test
    void unscopedQuestionsDropWeakMatchesAndReturnACompleteActionAnswer() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mockChatClient("Daniel will inspect the mail queue.",
                "Daniel will inspect the mail queue.", true);
        Document relevant = evidence("Daniel will inspect the mail queue.", 0, 0.64);
        Document weakMatchOne = evidence("Maya will ask the vendor for a replacement quote.", 1, 0.468);
        Document weakMatchTwo = evidence("The team agreed to keep using the east room.", 2, 0.46);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(relevant, weakMatchOne, weakMatchTwo));
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion("Who will inspect the mail queue?", null);

        assertEquals("Daniel will inspect the mail queue.", result.answer());
        assertEquals(1, result.sources().size());
        assertEquals("Daniel will inspect the mail queue.", relevant.getText());
    }

    @Test
    void repeatedUploadUsesTheSameChunkIdsSoPgvectorUpsertsRatherThanDuplicates() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<List<Document>> addedBatches = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            addedBatches.add(List.copyOf(invocation.getArgument(0)));
            return null;
        }).when(vectorStore).add(any());
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                mock(ChatClient.class));

        service.saveTranscript("Asha will measure response time.", "raspberry-pi", "meeting-retry-test");
        service.saveTranscript("Asha will measure response time.", "raspberry-pi", "meeting-retry-test");

        verify(vectorStore, times(2)).add(any());
        assertEquals(addedBatches.get(0).size(), addedBatches.get(1).size());
        for (int index = 0; index < addedBatches.get(0).size(); index++) {
            assertEquals(addedBatches.get(0).get(index).getId(), addedBatches.get(1).get(index).getId());
        }
    }

    @Test
    void answersDefinitionAcrossSpellingVariantsFromRetrievedEvidence() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        Document evidence = Document.builder()
                .text("The team met to prepare the SĀTHI office knowledge assistant demo. "
                        + "The goal is to show how employees can search internal knowledge in plain language "
                        + "and get answers grounded in saved sources.")
                .metadata(Map.of(
                        "meeting_id", "sathi-office-demo-20261009",
                        "chunk_index", 0,
                        "parent_document_id", "parent-1"))
                .score(0.57)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(evidence));
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion("What is saathi?", null);

        assertTrue(result.answer().contains("SĀTHI office knowledge assistant"));
        assertTrue(result.answer().contains("search internal knowledge in plain language"));
        assertEquals(1, result.sources().size());
        verify(chatClient, never()).prompt();
    }

    @Test
    void doesNotInferImpactForSubjectAbsentFromRetrievedEvidence() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        Document incidentEvidence = Document.builder()
                .text("The staging API outage was caused by an expired token. The incident did not affect production.")
                .metadata(Map.of(
                        "meeting_id", "sathi-office-demo-20261009",
                        "chunk_index", 2,
                        "parent_document_id", "parent-1"))
                .score(0.57)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(incidentEvidence));
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion(
                "Did the outage affect cricket?", "sathi-office-demo-20261009");

        assertEquals("I could not find enough information in the saved transcripts to answer that.",
                result.answer());
        assertTrue(result.sources().isEmpty());
        verify(chatClient, never()).prompt();
    }

    @Test
    void rejectsGeneratedAnswerWhenItsEvidenceQuoteIsNotInRetrievedTranscript() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mockChatClient("The incident affected production.",
                "The incident affected production.", true);
        Document evidence = evidence("The incident did not affect production.", 0, 0.61);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(evidence));
        ChatTranscriptServiceImpl service = new ChatTranscriptServiceImpl(
                vectorStore,
                TokenTextSplitter.builder().withChunkSize(350).build(),
                chatClient);

        var result = service.answerQuestion("What happened in the incident?", null);

        assertEquals("I could not find enough information in the saved transcripts to answer that.",
                result.answer());
        assertTrue(result.sources().isEmpty());
    }

    private static ChatClient mockChatClient(String answer, String quote, boolean supported) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any()))
                .thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.entity(
                org.mockito.ArgumentMatchers.eq(GroundedChatAnswer.class),
                org.mockito.ArgumentMatchers.<Consumer<ChatClient.EntityParamSpec>>any()))
                .thenReturn(new GroundedChatAnswer(answer, List.of(quote), supported));
        return chatClient;
    }

    private static Document evidence(String text, int chunkIndex, double score) {
        return Document.builder()
                .text(text)
                .metadata(Map.of(
                        "meeting_id", "meeting-reliability-test",
                        "chunk_index", chunkIndex,
                        "parent_document_id", "parent-" + chunkIndex))
                .score(score)
                .build();
    }
}
