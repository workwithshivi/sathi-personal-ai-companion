package org.stg.savan.savanserver.controller;

import org.stg.savan.savanserver.model.ApiErrorResponse;
import org.stg.savan.savanserver.model.QuestionRequest;
import org.stg.savan.savanserver.model.RetrievalAnswerResponse;
import org.stg.savan.savanserver.model.RetrievalResult;
import org.stg.savan.savanserver.model.TranscriptRequest;
import org.stg.savan.savanserver.model.TranscriptSaveResponse;
import org.stg.savan.savanserver.service.TranscriptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @PostMapping("/transcripts")
    public ResponseEntity<?> saveTranscript(
            @RequestBody TranscriptRequest request) {

        String transcriptText = request.combinedText();
        if (transcriptText.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty text array is required"));
        }

        try {
            String meetingId = transcriptService.saveTranscript(
                    transcriptText,
                    request.device(),
                    request.meetingId()
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(new TranscriptSaveResponse("saved", meetingId));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/qna")
    public ResponseEntity<?> answerQuestion(@RequestBody QuestionRequest request) {

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty question is required"));
        }

        RetrievalResult result;
        try {
            result = transcriptService.retrieve(
                    request.question().trim(),
                    request.meetingId()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse(e.getMessage()));
        }

        return ResponseEntity.ok(new RetrievalAnswerResponse(
                result.context(), result.intent(), result.sources()));
    }
}
