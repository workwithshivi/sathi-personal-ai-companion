package org.stg.savan.savanserver.controller;

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
            transcriptService.saveTranscript(
                    request.text(),
                    request.device()
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of("status", "saved"));

        } catch (IOException e) {
            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", "Could not save transcript"));
        }
    }

    @PostMapping("/qna")
    public ResponseEntity<Map<String, String>> answerQuestion(
            @RequestBody QnARequest request) {

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A non-empty question is required"));
        }

        String answer = transcriptService.answerQuestion(
                request.question().trim()
        );

        return ResponseEntity.ok(
                Map.of("answer", answer)
        );
    }

    public record TranscriptRequest(
            String text,
            String device
    ) {
    }

    public record QnARequest(
            String question
    ) {
    }
}