package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCalculationTest {

    @Test
    void calculatesResponseTimeAfterEvidenceBackedPercentageReduction() {
        String evidence = "The current measured response time on the Raspberry Pi is 18 seconds. "
                + "The team set a target to reduce that response time by 25 percent.";

        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "Using the transcript values, calculate the target response time after the planned reduction.",
                List.of(evidence));

        assertEquals("25% reduction from 18 seconds gives 13.5 seconds (18 × (1 - 25/100)).",
                result.orElseThrow());
    }

    @Test
    void supportsMillisecondsAndDecimalValues() {
        String evidence = "The current latency is 1250 milliseconds. The target is to reduce latency by 10%.";

        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "What is the new response time after the reduction?", List.of(evidence));

        assertEquals("10% reduction from 1250 milliseconds gives 1125 milliseconds "
                        + "(1250 × (1 - 10/100)).",
                result.orElseThrow());
    }

    @Test
    void doesNotCalculateWhenAnOperandIsMissingFromEvidence() {
        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "What is the target response time after the reduction?",
                List.of("The current measured response time is 18 seconds."));

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotTreatNumbersInTheQuestionAsTranscriptEvidence() {
        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "What is the new response time after a 25% reduction?",
                List.of("The meeting discussed response time but did not record a baseline or target percentage."));

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotCombineOperandsFromSeparateChunks() {
        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "What is the target response time after the reduction?",
                List.of(
                        "The current measured response time is 18 seconds.",
                        "The target is to reduce response time by 25 percent."));

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotApplyAnUnrelatedPercentageToResponseTime() {
        var result = EvidenceCalculation.calculateResponseTimeReduction(
                "What is the target response time after the reduction?",
                List.of("The current response time is 18 seconds. Reduce storage costs by 25 percent."));

        assertTrue(result.isEmpty());
    }
}
