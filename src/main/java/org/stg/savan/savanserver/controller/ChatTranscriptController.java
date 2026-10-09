package org.stg.savan.savanserver.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.model.ApiErrorResponse;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.QuestionRequest;
import org.stg.savan.savanserver.model.TranscriptRequest;
import org.stg.savan.savanserver.model.TranscriptSaveResponse;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatTranscriptController {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptController.class);

    private final ChatTranscriptService chatTranscriptService;

    public ChatTranscriptController(ChatTranscriptService chatTranscriptService) {
        this.chatTranscriptService = chatTranscriptService;
    }

    @PostMapping("/ai/transcripts")
    public ResponseEntity<?> saveTranscript(
            @RequestBody TranscriptRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty text field is required"));
        }

        try {
            String meetingId = chatTranscriptService.saveTranscript(
                    request.text(), request.device(), request.meetingId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new TranscriptSaveResponse("saved", meetingId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Could not save transcript for ChatClient pipeline", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse("Transcript storage is unavailable"));
        }
    }

    @PostMapping("/ai/qna")
    public ResponseEntity<?> answerQuestion(@RequestBody QuestionRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty question field is required"));
        }

        try {
            ChatAnswerResult result = chatTranscriptService.answerQuestion(
                    request.question().trim(), request.meetingId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("ChatClient Q&A request failed", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse("Chat answering is temporarily unavailable"));
        }
    }
}
