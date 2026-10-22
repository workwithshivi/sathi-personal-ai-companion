package org.stg.savan.savanserver.service.impl;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calculates evidence-backed percentage changes for any metric, when its operands are explicit. */
final class EvidenceCalculation {

    private static final Pattern BASELINE = Pattern.compile(
            "(?is)\\b(?:current|baseline|starting|initial|original)\\s+"
                    + "(?<metric>[^.!?\\n]{1,120}?)\\s+\\b(?:is|was|at|of|takes?|took|requires?|required|equals?)\\s+"
                    + "(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>[\\p{L}µμ$€£][\\p{L}\\p{N}µμ$€£/_-]*)?");
    private static final Pattern CHANGE_AFTER_VERB = Pattern.compile(
            "(?is)\\b(?<verb>reduce|reduction|decrease|decreased|cut|lower(?:ed|ing)?|"
                    + "increase|increased|raise|raised|grow|grew|growth)\\b"
                    + "(?<metric>[^.!?]{0,100}?)(?<percent>\\d+(?:\\.\\d+)?)\\s*(?:%|percent\\b)");
    private static final Pattern CHANGE_BEFORE_VERB = Pattern.compile(
            "(?is)(?<percent>\\d+(?:\\.\\d+)?)\\s*(?:%|percent\\b)"
                    + "(?<middle>[^.!?]{0,45}?)\\b(?<verb>reduction|decrease|increase|growth)\\b"
                    + "(?<metric>[^.!?]{0,100})");
    private static final Pattern RESULT_QUESTION = Pattern.compile(
            "(?is)\\b(?:what|calculate|compute|work out|determine)\\b.{0,100}"
                    + "\\b(?:result|resulting|new|final|target value|after|end up)\\b");
    private static final Set<String> NON_METRIC_WORDS = Set.of(
            "a", "an", "and", "at", "by", "for", "from", "in", "is", "it", "of", "on", "or",
            "original", "same", "the", "that", "this", "to", "was", "were", "with", "current",
            "baseline", "starting", "initial", "measured", "planned", "target", "value", "amount");
    private static final Pattern REFERENTIAL_METRIC = Pattern.compile(
            "(?iu)\\b(?:it|this|that|its|same)\\b");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private EvidenceCalculation() {
    }

    static Optional<String> calculatePercentageChange(String question, List<String> evidenceChunks) {
        if (question == null || evidenceChunks == null || !RESULT_QUESTION.matcher(question).find()) {
            return Optional.empty();
        }
        for (String evidence : evidenceChunks) {
            Optional<String> result = calculateFromOneChunk(evidence);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> calculateFromOneChunk(String evidence) {
        if (evidence == null) {
            return Optional.empty();
        }
        Matcher baseline = BASELINE.matcher(evidence);
        Matcher change = CHANGE_AFTER_VERB.matcher(evidence);
        if (!baseline.find()) {
            return Optional.empty();
        }
        Matcher baselineCheck = BASELINE.matcher(evidence);
        if (baselineCheck.find() && baselineCheck.find()) {
            return Optional.empty();
        }
        long changeCount = CHANGE_AFTER_VERB.matcher(evidence).results().count()
                + CHANGE_BEFORE_VERB.matcher(evidence).results().count();
        if (changeCount != 1) {
            return Optional.empty();
        }
        Matcher reversedChange = CHANGE_BEFORE_VERB.matcher(evidence);
        boolean hasChange = change.find();
        boolean changeFollowsVerb = hasChange;
        if (!hasChange) {
            hasChange = reversedChange.find();
        }
        if (!hasChange) {
            return Optional.empty();
        }

        String metric = baseline.group("metric");
        String changeMetric;
        String verb;
        String percentageText;
        if (!changeFollowsVerb) {
            verb = reversedChange.group("verb");
            changeMetric = reversedChange.group("metric");
            percentageText = reversedChange.group("percent");
        } else {
            verb = change.group("verb");
            changeMetric = change.group("metric");
            percentageText = change.group("percent");
        }
        if (!sameMetric(metric, changeMetric)
                && !REFERENTIAL_METRIC.matcher(changeMetric).find()) {
            return Optional.empty();
        }

        BigDecimal value = new BigDecimal(baseline.group("value"));
        BigDecimal percentage = new BigDecimal(percentageText);
        if (value.signum() < 0 || percentage.signum() < 0 || percentage.compareTo(ONE_HUNDRED) > 0) {
            return Optional.empty();
        }
        boolean increase = verb.toLowerCase(Locale.ROOT).matches("increase|increased|raise|raised|grow|grew|growth");
        BigDecimal factor = increase
                ? ONE_HUNDRED.add(percentage)
                : ONE_HUNDRED.subtract(percentage);
        BigDecimal result = value.multiply(factor).divide(ONE_HUNDRED);
        String operation = increase ? "increase" : "reduction";
        String valueText = format(value);
        String percentageValue = format(percentage);
        String unit = baseline.group("unit");
        String unitText = unit == null ? "" : " " + unit;
        return Optional.of(percentageValue + "% " + operation + " from " + valueText + unitText
                + " gives " + format(result) + unitText + " (" + valueText + " × (1 "
                + (increase ? "+" : "-") + " " + percentageValue + "/100)).");
    }

    private static boolean sameMetric(String baselineMetric, String changeMetric) {
        Set<String> baselineWords = metricWords(baselineMetric);
        Set<String> changeWords = metricWords(changeMetric);
        return !baselineWords.isEmpty() && baselineWords.stream().anyMatch(changeWords::contains);
    }

    private static Set<String> metricWords(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return Pattern.compile("[\\p{L}\\p{N}]+")
                .matcher(normalized)
                .results()
                .map(match -> match.group())
                .filter(word -> word.length() > 2 && !NON_METRIC_WORDS.contains(word))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
