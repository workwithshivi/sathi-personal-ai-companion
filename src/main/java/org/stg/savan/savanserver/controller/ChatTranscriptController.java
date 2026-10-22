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

import java.util.Objects;

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
        long startedAt = System.nanoTime();
        String transcriptText = request.combinedText();
        int segmentCount = request.text() == null ? 0 : (int) request.text().stream()
                .filter(Objects::nonNull)
                .filter(segment -> !segment.isBlank())
                .count();
        log.info("Received /ai/transcripts request | requested_meeting_id={} | device={} | segments={} | characters={}",
                request.meetingId(), request.device(), segmentCount, transcriptText.length());
        log.info("Full /ai/transcripts content | requested_meeting_id={} | transcript=\n{}",
                request.meetingId(), transcriptText);

        if (transcriptText.isBlank()) {
            log.info("Rejected /ai/transcripts request | reason=empty_text");
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty text array is required"));
        }

        try {
            String meetingId = chatTranscriptService.saveTranscript(
                    transcriptText, request.device(), request.meetingId());
            log.info("Completed /ai/transcripts request | meeting_id={} | characters={} | elapsed_ms={}",
                    meetingId, transcriptText.length(), elapsedMillis(startedAt));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new TranscriptSaveResponse("saved", meetingId));
        } catch (IllegalArgumentException e) {
            log.info("Rejected /ai/transcripts request | reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.info("Failed /ai/transcripts request | exception={} | message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse("Transcript storage is unavailable"));
        }
    }

    @PostMapping("/ai/qna")
    public ResponseEntity<?> answerQuestion(@RequestBody QuestionRequest request) {
        long startedAt = System.nanoTime();
        String question = request.question();
        log.info("Received /ai/qna request | meeting_id={} | question_characters={}",
                request.meetingId(), question == null ? 0 : question.length());
        log.info("Exact /ai/qna question | meeting_id={} | question={}", request.meetingId(), question);

        if (question == null || question.isBlank()) {
            log.info("Rejected /ai/qna request | reason=empty_question");
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty question field is required"));
        }

        try {
            ChatAnswerResult result = chatTranscriptService.answerQuestion(
                    question.trim(), request.meetingId());
            log.info("Completed /ai/qna request | meeting_id={} | sources={} | answer_characters={} | elapsed_ms={}",
                    result.meetingId(), result.sources().size(), result.answer().length(), elapsedMillis(startedAt));
            log.info("Exact /ai/qna answer | meeting_id={} | answer=\n{}", result.meetingId(), result.answer());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.info("Rejected /ai/qna request | reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.info("Failed /ai/qna request | meeting_id={} | exception={} | message={}",
                    request.meetingId(), e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse("Chat answering is temporarily unavailable"));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
