package org.stg.savan.savanserver.service.support;

import org.springframework.ai.document.Document;
import org.stg.savan.savanserver.constants.SathiConstants;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure transformations and checks used to keep Q&A orchestration focused on its workflow. */
public final class QnaEvidenceSupport {

    private static final Pattern DEFINITION_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:what\\s+is|what's|who\\s+is|define|explain|describe)\\s+"
                    + "(?:the\\s+)?(.+?)\\s*[?.!]*\\s*$");

    private QnaEvidenceSupport() {
    }

    public static java.util.Optional<String> answerDefinitionFromEvidence(
            String question, List<Document> documents) {
        var matcher = DEFINITION_QUESTION.matcher(question);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        String entity = matcher.group(1).replaceAll("[?.!]+$", "").trim();
        String normalizedEntity = normalizeForMatching(entity);
        if (normalizedEntity.isBlank()) {
            return java.util.Optional.empty();
        }

        for (Document document : documents) {
            String[] sentences = document.getText().split("(?<=[.!?])\\s+|\\R+");
            for (int index = 0; index < sentences.length; index++) {
                String sentence = sentences[index].trim();
                if (normalizeForMatching(sentence).contains(normalizedEntity)) {
                    String answer = sentence;
                    if (index + 1 < sentences.length && !sentences[index + 1].isBlank()) {
                        answer += " " + sentences[index + 1].trim();
                    }
                    return java.util.Optional.of(answer);
                }
            }
        }
        return java.util.Optional.empty();
    }

    public static EvidenceQuoteCheck checkEvidenceQuotes(List<String> quotes, List<Document> documents) {
        if (quotes == null || quotes.isEmpty()) {
            return new EvidenceQuoteCheck(false, 0, 0);
        }
        List<String> normalizedDocuments = documents.stream()
                .map(document -> normalizeEvidenceText(document.getText()))
                .toList();
        int validCount = (int) quotes.stream()
                .filter(quote -> quote != null && !quote.isBlank())
                .filter(quote -> {
                    String normalizedQuote = normalizeEvidenceText(quote);
                    return !normalizedQuote.isBlank()
                            && normalizedDocuments.stream().anyMatch(text -> text.contains(normalizedQuote));
                })
                .count();
        return new EvidenceQuoteCheck(validCount == quotes.size(), validCount, quotes.size());
    }

    public static boolean containsTerm(String text, String term) {
        return Pattern.compile("(?iu)\\b" + Pattern.quote(term) + "\\b")
                .matcher(text == null ? "" : text)
                .find();
    }

    public static String meetingIdFilter(String meetingId) {
        String pipelineFilter = "pipeline == '" + SathiConstants.CHAT_PIPELINE + "'";
        return meetingId == null ? pipelineFilter : pipelineFilter + " && meeting_id == '" + meetingId + "'";
    }

    public static String stableChunkId(String meetingId, int chunkIndex, String text) {
        String key = meetingId + "\n" + chunkIndex + "\n" + text;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeForMatching(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("([aeiou])\\1+", "$1");
    }

    private static String normalizeEvidenceText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public record EvidenceQuoteCheck(boolean valid, int validCount, int totalCount) {
    }
}
