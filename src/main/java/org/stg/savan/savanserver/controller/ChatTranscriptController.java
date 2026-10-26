package org.stg.savan.savanserver.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.model.ApiErrorResponse;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.QuestionRequest;
import org.stg.savan.savanserver.model.TranscriptRequest;
import org.stg.savan.savanserver.model.TranscriptSaveResponse;
import org.stg.savan.savanserver.service.ChatTranscriptService;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Exposes the transcript-ingestion and Q&A HTTP endpoints and maps service outcomes to HTTP responses. */
@RestController
public class ChatTranscriptController {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptController.class);

    private final ChatTranscriptService chatTranscriptService;

    @Value("${sathi.logging.include-content:false}")
    private boolean includeContentInLogs;

    /** Injects the application service used by both HTTP endpoints. */
    public ChatTranscriptController(ChatTranscriptService chatTranscriptService) {
        this.chatTranscriptService = chatTranscriptService;
    }

    /** Validates and accepts transcript batches, then returns the generated or supplied meeting ID. */
    @PostMapping("/ai/transcripts")
    public ResponseEntity<?> saveTranscript(
            @RequestBody TranscriptRequest request) {
        long startedAt = System.nanoTime();
        String transcriptText = request.combinedText();
        int segmentCount = request.text() == null ? 0 : (int) request.text().stream()
                .filter(Objects::nonNull)
                .filter(segment -> !segment.isBlank())
                .count();
        ApiRequestLogSupport.info(log,
                "Received /ai/transcripts request | requested_meeting_id={} | device={} | segments={} | characters={}",
                request.meetingId(), request.device(), segmentCount, transcriptText.length());
        if (includeContentInLogs) {
            ApiRequestLogSupport.infoWhen(log, includeContentInLogs,
                    "Full /ai/transcripts content | requested_meeting_id={} | transcript=\n{}",
                    request.meetingId(), transcriptText);
        }

        if (transcriptText.isBlank()) {
            ApiRequestLogSupport.info(log, "Rejected /ai/transcripts request | reason=empty_text");
            return ResponseEntity.badRequest()
                    .body(new ApiErrorResponse("A non-empty text array is required"));
        }

        try {
            String meetingId = chatTranscriptService.saveTranscript(
                    transcriptText, request.device(), request.meetingId());
            ApiRequestLogSupport.debug(log,
                    "Completed /ai/transcripts request | meeting_id={} | characters={} | elapsed_ms={}",
                    meetingId, transcriptText.length(), ApiRequestLogSupport.elapsedMillis(startedAt));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new TranscriptSaveResponse("saved", meetingId));
        } catch (IllegalArgumentException e) {
            ApiRequestLogSupport.info(log, "Rejected /ai/transcripts request | reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            ApiRequestLogSupport.info(log, "Failed /ai/transcripts request | exception={} | message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse("Transcript storage is unavailable"));
        }
    }

    /** Validates a question request, delegates Q&A, and maps validation or service failures to HTTP status. */
    @PostMapping("/ai/qna")
    public ResponseEntity<?> answerQuestion(@RequestBody QuestionRequest request) {
        long startedAt = System.nanoTime();
        String question = request.question();
        ApiRequestLogSupport.info(log, "Received /ai/qna request | meeting_id={} | question_characters={}",
                request.meetingId(), question == null ? 0 : question.length());
        if (question == null || question.isBlank()) {
            ApiErrorResponse response = new ApiErrorResponse("A non-empty question field is required");
            ApiRequestLogSupport.info(log, "Rejected /ai/qna request | reason=empty_question");
            ApiRequestLogSupport.apiExchange(log, "/ai/qna", HttpStatus.BAD_REQUEST.value(), request, response);
            return ResponseEntity.badRequest().body(response);
        }

        try {
            ChatAnswerResult result = chatTranscriptService.answerQuestion(
                    question.trim(), request.meetingId(), request.conversationId());
            ApiRequestLogSupport.info(log,
                    "Completed /ai/qna request | meeting_id={} | sources={} | answer_characters={} | elapsed_ms={}",
                    result.meetingId(), result.sources().size(), result.answer().length(),
                    ApiRequestLogSupport.elapsedMillis(startedAt));
            ApiRequestLogSupport.apiExchange(log, "/ai/qna", HttpStatus.OK.value(), request, result);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            ApiErrorResponse response = new ApiErrorResponse(e.getMessage());
            ApiRequestLogSupport.info(log, "Rejected /ai/qna request | reason={}", e.getMessage());
            ApiRequestLogSupport.apiExchange(log, "/ai/qna", HttpStatus.BAD_REQUEST.value(), request, response);
            return ResponseEntity.badRequest().body(response);
        } catch (RuntimeException e) {
            ApiErrorResponse response = new ApiErrorResponse("Chat answering is temporarily unavailable");
            ApiRequestLogSupport.info(log, "Failed /ai/qna request | meeting_id={} | exception={} | message={}",
                    request.meetingId(), e.getClass().getSimpleName(), e.getMessage());
            ApiRequestLogSupport.debug(log, "/ai/qna failure stack trace | meeting_id={}", request.meetingId(), e);
            ApiRequestLogSupport.apiExchange(log, "/ai/qna", HttpStatus.SERVICE_UNAVAILABLE.value(), request, response);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }

}
