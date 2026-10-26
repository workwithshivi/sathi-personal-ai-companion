package org.stg.savan.savanserver.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.ChatAnswerResult;
import org.stg.savan.savanserver.model.RetrievalSource;
import org.stg.savan.savanserver.service.support.QnaEvidenceSupport;
import org.stg.savan.savanserver.service.support.TranscriptDocumentSupport;
import org.stg.savan.savanserver.util.ApiRequestLogSupport;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Resolves question forms that can be answered directly and safely from retrieved evidence. */
@Service
public class DeterministicEvidenceAnswerer {

    private static final Logger log = LoggerFactory.getLogger(DeterministicEvidenceAnswerer.class);

    /**
     * Tries direct evidence handlers for action, decision, issue, yes/no, relation, definition,
     * and supported percentage-change questions. The result indicates whether model generation
     * should stop, continue, or abstain because a required direct fact was not found.
     */
    public Resolution resolve(
            String question,
            String intent,
            String requiredSubject,
            String meetingId,
            List<Document> evidence,
            boolean includeAnswerInLogs) {
        if ("ACTION_ITEM".equals(intent)) {
            Optional<String> answer = QnaEvidenceSupport.answerActionFromEvidence(question, evidence);
            return directOrAbstain(answer, meetingId, evidence,
                    "Answered action question from matching transcript sentences",
                    "No directly matching action sentence found; abstaining", includeAnswerInLogs);
        }
        if ("DECISION".equals(intent)) {
            Optional<String> answer = QnaEvidenceSupport.answerDecisionFromEvidence(question, evidence);
            return directOrAbstain(answer, meetingId, evidence,
                    "Answered decision question from matching transcript sentences",
                    "No directly matching decision sentence found; abstaining", includeAnswerInLogs);
        }
        if ("ISSUE".equals(intent)) {
            Optional<String> answer = QnaEvidenceSupport.answerIssueFromEvidence(question, evidence);
            return directOrContinue(answer, meetingId, evidence,
                    "Answered issue question directly from matching transcript sentence");
        }
        if ("YES_NO".equals(intent)) {
            Optional<String> answer = QnaEvidenceSupport.answerYesNoFromEvidence(question, evidence);
            return directOrAbstain(answer, meetingId, evidence,
                    "Answered yes/no question directly from matching transcript sentence",
                    "No single sentence directly answers yes/no question; abstaining", includeAnswerInLogs);
        }
        if ("RELATION".equals(intent)) {
            Optional<String> answer = QnaEvidenceSupport.answerRelationFromEvidence(requiredSubject, evidence);
            return directOrAbstain(answer, meetingId, evidence,
                    "Answered relation question from an explicit matching sentence",
                    "No explicit relation sentence found; abstaining", includeAnswerInLogs);
        }

        if ("DEFINITION".equals(intent)) {
            Optional<String> definition = QnaEvidenceSupport.answerDefinitionFromEvidence(question, evidence);
            if (definition.isPresent()) {
                ApiRequestLogSupport.info(log,
                        "Answered entity definition directly from transcript evidence | meeting_id={} | answer_characters={}",
                        meetingId, definition.get().length());
                ApiRequestLogSupport.infoWhen(log, includeAnswerInLogs,
                        "Exact deterministic /ai/qna answer | meeting_id={} | answer=\n{}", meetingId, definition.get());
                return Resolution.answered(result(definition.get(), meetingId, evidence, List.of(definition.get())));
            }
        }

        Optional<EvidenceCalculation.CalculationResult> calculation =
                EvidenceCalculation.calculatePercentageChangeWithEvidence(
                        question, evidence.stream().map(Document::getText).toList());
        if (calculation.isPresent()) {
            EvidenceCalculation.CalculationResult value = calculation.get();
            List<Document> supporting = evidence.stream()
                    .filter(document -> document.getText().equals(value.evidence()))
                    .toList();
            ApiRequestLogSupport.info(log,
                    "Calculated percentage change from retrieved transcript evidence | meeting_id={} | sources={}",
                    meetingId, supporting.size());
            ApiRequestLogSupport.infoWhen(log, includeAnswerInLogs,
                    "Exact deterministic /ai/qna answer | meeting_id={} | answer=\n{}", meetingId, value.answer());
            return Resolution.answered(new ChatAnswerResult(value.answer(), meetingId,
                    supporting.stream().map(TranscriptDocumentSupport::toSource).toList()));
        }
        return Resolution.continueToModel();
    }

    /** Builds a source-limited result when a direct evidence rule succeeds. */
    private static Resolution directOrContinue(
            Optional<String> answer,
            String meetingId,
            List<Document> evidence,
            String successLog) {
        if (answer.isEmpty()) {
            return Resolution.continueToModel();
        }
        ApiRequestLogSupport.info(log, successLog + " | meeting_id={}", meetingId);
        return Resolution.answered(result(answer.get(), meetingId, evidence, List.of(answer.get())));
    }

    /** Builds a direct result or marks the request for transcript-miss fallback. */
    private static Resolution directOrAbstain(
            Optional<String> answer,
            String meetingId,
            List<Document> evidence,
            String successLog,
            String missLog,
            boolean includeAnswerInLogs) {
        if (answer.isEmpty()) {
            ApiRequestLogSupport.info(log, missLog + " | meeting_id={}", meetingId);
            return Resolution.abstain();
        }
        ApiRequestLogSupport.info(log, successLog + " | meeting_id={}", meetingId);
        ApiRequestLogSupport.infoWhen(log, includeAnswerInLogs,
                "Exact deterministic /ai/qna answer | meeting_id={} | answer=\n{}", meetingId, answer.get());
        return Resolution.answered(result(answer.get(), meetingId, evidence, List.of(answer.get())));
    }

    /** Creates a response whose sources contain the sentence used to support the answer. */
    private static ChatAnswerResult result(
            String answer, String meetingId, List<Document> evidence, List<String> supportingText) {
        List<RetrievalSource> sources = QnaEvidenceSupport
                .documentsContainingEvidence(supportingText, evidence).stream()
                .map(TranscriptDocumentSupport::toSource)
                .toList();
        return new ChatAnswerResult(answer, meetingId, sources);
    }

    /** Describes whether deterministic logic answered, requires abstention, or should defer to the model. */
    public record Resolution(boolean terminal, Optional<ChatAnswerResult> answer) {
        /** Returns a completed deterministic answer. */
        public static Resolution answered(ChatAnswerResult answer) {
            return new Resolution(true, Optional.of(answer));
        }

        /** Stops model generation because a strict direct-evidence handler found no answer. */
        public static Resolution abstain() {
            return new Resolution(true, Optional.empty());
        }

        /** Allows grounded model generation to continue. */
        public static Resolution continueToModel() {
            return new Resolution(false, Optional.empty());
        }
    }
}
