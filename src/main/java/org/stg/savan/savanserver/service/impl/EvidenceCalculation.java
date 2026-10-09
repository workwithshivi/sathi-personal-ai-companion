package org.stg.savan.savanserver.service.impl;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs a small set of exact calculations only when all operands appear in retrieved evidence. */
final class EvidenceCalculation {

    private static final Pattern BASELINE_TIME = Pattern.compile(
            "(?is)\\b(?:current|baseline)\\b.{0,120}?\\b(?:response\\s+time|latency)\\b"
                    + ".{0,80}?(\\d+(?:\\.\\d+)?)\\s*(milliseconds?|ms|seconds?|secs?)\\b");
    private static final Pattern PERCENT_REDUCTION = Pattern.compile(
            "(?is)(?:\\b(?:reduce|decrease|decreased|cut|lower(?:ed|ing)?)\\b"
                    + "[^.!?]{0,80}\\b(?:response\\s+time|latency)\\b[^.!?]{0,50}?"
                    + "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent\\b)|"
                    + "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent\\b)[^.!?]{0,50}"
                    + "\\b(?:reduction|decrease)\\b[^.!?]{0,80}"
                    + "\\b(?:response\\s+time|latency)\\b)");
    private static final Pattern RESPONSE_TIME_QUESTION = Pattern.compile(
            "(?i)\\b(?:latency|response\\s+time)\\b");
    private static final Pattern REDUCTION_QUESTION = Pattern.compile(
            "(?i)\\b(?:reduce|reduction|decrease|decreased|cut|lower|reduced)\\b");
    private static final Pattern TARGET_QUESTION = Pattern.compile(
            "(?i)\\b(?:target|new|resulting|after)\\b");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private EvidenceCalculation() {
    }

    static Optional<String> calculateResponseTimeReduction(String question, List<String> evidenceChunks) {
        if (question == null || evidenceChunks == null || !asksForResponseTimeReduction(question)) {
            return Optional.empty();
        }

        for (String evidence : evidenceChunks) {
            Optional<String> answer = calculateFromOneChunk(evidence);
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> calculateFromOneChunk(String evidence) {
        Matcher baselineMatcher = BASELINE_TIME.matcher(evidence);
        Matcher reductionMatcher = PERCENT_REDUCTION.matcher(evidence);
        if (!baselineMatcher.find() || !reductionMatcher.find()) {
            return Optional.empty();
        }

        BigDecimal baseline = new BigDecimal(baselineMatcher.group(1));
        String percentageValue = reductionMatcher.group(1) != null
                ? reductionMatcher.group(1)
                : reductionMatcher.group(2);
        BigDecimal percentage = new BigDecimal(percentageValue);
        if (baseline.signum() < 0 || percentage.signum() < 0 || percentage.compareTo(ONE_HUNDRED) > 0) {
            return Optional.empty();
        }

        BigDecimal result = baseline.multiply(ONE_HUNDRED.subtract(percentage))
                .divide(ONE_HUNDRED);
        String baselineText = format(baseline);
        String percentageText = format(percentage);
        String resultText = format(result);
        String unit = normalizeUnit(baselineMatcher.group(2));

        return Optional.of(percentageText + "% reduction from " + baselineText + " " + unit
                + " gives " + resultText + " " + unit + " (" + baselineText + " × (1 - "
                + percentageText + "/100)).");
    }

    private static boolean asksForResponseTimeReduction(String question) {
        return RESPONSE_TIME_QUESTION.matcher(question).find()
                && REDUCTION_QUESTION.matcher(question).find()
                && TARGET_QUESTION.matcher(question).find();
    }

    private static String normalizeUnit(String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT);
        return normalized.startsWith("m") ? "milliseconds" : "seconds";
    }

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
