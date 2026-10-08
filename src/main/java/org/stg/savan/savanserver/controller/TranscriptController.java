package org.stg.savan.savanserver.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.stg.savan.savanserver.model.RetrievalResult;
import org.stg.savan.savanserver.service.TranscriptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @PostMapping("/transcripts")
    public ResponseEntity<Map<String, String>> saveTranscript(
            @RequestBody TranscriptRequest request) {

        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A non-empty text field is required"));
        }

        try {
            String meetingId = transcriptService.saveTranscript(
                    request.text(),
                    request.device()
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of("status", "saved", "meeting_id", meetingId));

        } catch (IOException e) {
            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", "Could not save transcript"));
        }
    }

    @PostMapping("/qna")
    public ResponseEntity<Map<String, Object>> answerQuestion(
            @RequestBody QnARequest request) {

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A non-empty question is required"));
        }

        RetrievalResult result;
        try {
            result = transcriptService.retrieve(
                    request.question().trim(),
                    request.meetingId()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                "answer", result.context(),
                "intent", result.intent(),
                "sources", result.sources()
        ));
    }

    public record TranscriptRequest(
            String text,
            String device
    ) {
    }

    public record QnARequest(
            String question,
            @JsonProperty("meeting_id") String meetingId
    ) {
    }
}
