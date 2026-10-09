package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

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

class ChatTranscriptServiceImplTest {

    @Test
    void retriesExpandedIncidentImpactQueriesAtFallbackThresholdWithinMeetingScope() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mockChatClient("No, the staging incident did not affect production.");
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
                    && request.getQuery().contains("incident or outage")) {
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

    private static ChatClient mockChatClient(String answer) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any()))
                .thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn(answer);
        return chatClient;
    }
}
