package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCalculationTest {

    @Test
    void calculatesPercentageReductionForARecordedMetric() {
        String evidence = "The current measured response time on the Raspberry Pi is 18 seconds. "
                + "The team set a target to reduce that response time by 25 percent.";

        var result = EvidenceCalculation.calculatePercentageChange(
                "Using the transcript values, calculate the target response time after the planned reduction.",
                List.of(evidence));

        assertEquals("25% reduction from 18 seconds gives 13.5 seconds (18 × (1 - 25/100)).",
                result.orElseThrow());
    }

    @Test
    void calculatesPercentageIncreaseForAnUnrelatedDomainAndUnit() {
        String evidence = "The current inventory level is 200 units. "
                + "The plan is to increase that inventory level by 15 percent.";

        var result = EvidenceCalculation.calculatePercentageChange(
                "What is the resulting inventory level after the planned increase?", List.of(evidence));

        assertEquals("15% increase from 200 units gives 230 units (200 × (1 + 15/100)).",
                result.orElseThrow());
    }

    @Test
    void calculatesFromNaturalBaselineAndReferentialChangeWording() {
        String evidence = "The current report generation takes 20 seconds. "
                + "The team set a goal to reduce this time by 30 percent.";

        var result = EvidenceCalculation.calculatePercentageChange(
                "What is the target time after the planned reduction?", List.of(evidence));

        assertEquals("30% reduction from 20 seconds gives 14 seconds (20 × (1 - 30/100)).",
                result.orElseThrow());
    }

    @Test
    void doesNotApplyAChangeForAnUnrelatedMetric() {
        var result = EvidenceCalculation.calculatePercentageChange(
                "What is the new response time after the reduction?",
                List.of("The current response time is 18 seconds. Reduce storage costs by 25 percent."));

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotCalculateWhenAnOperandIsMissingOrOnlyAppearsInTheQuestion() {
        assertTrue(EvidenceCalculation.calculatePercentageChange(
                "What is the target value after the reduction?",
                List.of("The current metric was 18 units, but no change percentage was recorded.")).isEmpty());
        assertTrue(EvidenceCalculation.calculatePercentageChange(
                "What is the new value after a 25% reduction?",
                List.of("The transcript mentions a value but records neither its baseline nor a change.")).isEmpty());
    }

    @Test
    void doesNotCombineOperandsFromSeparateChunks() {
        var result = EvidenceCalculation.calculatePercentageChange(
                "What is the resulting response time after the reduction?",
                List.of("The current response time is 18 seconds.",
                        "The target is to reduce response time by 25 percent."));

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotCalculateWhenTheQuestionIsNotAskingForAResult() {
        var result = EvidenceCalculation.calculatePercentageChange(
                "What percentage reduction was planned?",
                List.of("The current storage capacity is 100 units. Increase that storage capacity by 20 percent."));

        assertTrue(result.isEmpty());
    }
}
