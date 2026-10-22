package org.stg.savan.savanserver.service.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QnaEvidenceSupportTest {

    @Test
    void relationPolarityComesFromTheClauseContainingTheRequestedSubject() {
        Document evidence = new Document("The outage did not affect staging, but it affected production.");

        var answer = QnaEvidenceSupport.answerRelationFromEvidence("production", List.of(evidence));

        assertEquals("Yes. It affected production.", answer.orElseThrow());
    }

    @Test
    void relationNegationAppliesWhenItIsInTheRequestedSubjectsClause() {
        Document evidence = new Document("The outage affected staging, but it did not affect production.");

        var answer = QnaEvidenceSupport.answerRelationFromEvidence("production", List.of(evidence));

        assertEquals("No. It did not affect production.", answer.orElseThrow());
    }

    @Test
    void yesNoPolarityIgnoresNegationInAnUnrelatedClause() {
        Document evidence = new Document("The release date changed, but the budget was not approved.");

        var answer = QnaEvidenceSupport.answerYesNoFromEvidence(
                "Was the release date changed?", List.of(evidence));

        assertEquals("Yes. The release date changed", answer.orElseThrow());
    }
}
