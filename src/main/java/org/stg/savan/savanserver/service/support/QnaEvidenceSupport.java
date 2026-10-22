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
    private static final Pattern ISSUE_INDICATOR = Pattern.compile(
            "(?iu)\\b(?:issue|problem|fault|malfunction|fail\\w*|broken|flicker\\w*|damag\\w*|"
                    + "stuck|jam\\w*|unavailable|error|defect|repair|leak\\w*|crack\\w*|not working)\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?iu)\\b(?:no|not|never|neither|without|isn't|aren't|wasn't|weren't|didn't|doesn't|hasn't|haven't)\\b");
    private static final Pattern ACTION_SENTENCE = Pattern.compile(
            "(?iu)\\b(?:will|must|should|assigned|responsible|tasked|needs? to|plans? to|follow[ -]?up)\\b");
    private static final Pattern DECISION_SENTENCE = Pattern.compile(
            "(?iu)\\b(?:decided|decide|chose|choose|chosen|selected|select|picked|agreed|approved|rejected|postponed|voted)\\b");
    private static final Pattern RELATION_SENTENCE = Pattern.compile(
            "(?iu)\\b(?:affect\\w*|impact\\w*|influenc\\w*|chang\\w*|alter\\w*|delay\\w*|block\\w*|"
                    + "prevent\\w*|disrupt\\w*|caus\\w*|increas\\w*|decreas\\w*|improv\\w*|reduc\\w*)\\b");
    private static final Pattern QUESTION_CONSTRAINT = Pattern.compile(
            "(?iu)\\b(?:today|tomorrow|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
                    + "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|"
                    + "aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|"
                    + "\\d+(?:st|nd|rd|th)?(?:[./-]\\d{1,4})*%?)\\b");
    private static final java.util.Set<String> ACTION_QUERY_TERMS = java.util.Set.of(
            "todo", "action", "actions", "item", "items", "task", "tasks", "next", "step", "steps",
            "follow", "up", "assigned", "responsible", "owner", "owners", "will", "should", "must",
            "benchmark", "benchmarked", "do", "done");
    private static final java.util.Set<String> DECISION_QUERY_TERMS = java.util.Set.of(
            "decid", "decision", "decisions", "choos", "choice", "choices", "select", "selected",
            "option", "options", "agree", "agreed", "team", "group", "meeting", "approach", "strategy");
    private static final java.util.Set<String> QUESTION_FILLER_WORDS = java.util.Set.of(
            "a", "an", "any", "are", "be", "been", "being", "can", "could", "did", "do", "does",
            "for", "from", "get", "got", "had", "has", "have", "how", "i", "in", "is", "it", "me",
            "my", "of", "on", "or", "the", "their", "them", "this", "that", "to", "was", "we", "were",
            "what", "when", "where", "which", "who", "why", "will", "with", "would", "you", "your", "team");
    private static final java.util.Set<String> QUESTION_GENERIC_TERMS = java.util.Set.of(
            "about", "regarding", "current", "currently", "recorded", "saved", "information", "detail",
            "details", "transcript");

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

    /** Finds a transcript sentence that both names the requested subject and explicitly states a problem. */
    public static java.util.Optional<String> answerIssueFromEvidence(String question, List<Document> documents) {
        java.util.Set<String> questionTerms = contentTerms(question);
        if (questionTerms.isEmpty()) {
            return java.util.Optional.empty();
        }
        return documents.stream()
                .flatMap(document -> java.util.Arrays.stream(document.getText().split("(?<=[.!?])\\s+|\\R+")))
                .map(String::trim)
                .filter(sentence -> ISSUE_INDICATOR.matcher(sentence).find())
                .filter(sentence -> overlapCount(questionTerms, contentTerms(sentence)) >= Math.min(2, questionTerms.size()))
                .findFirst();
    }

    /** Returns only source sentences that directly state matching assigned work. */
    public static java.util.Optional<String> answerActionFromEvidence(String question, List<Document> documents) {
        java.util.Set<String> focusTerms = contentTerms(question).stream()
                .filter(term -> !ACTION_QUERY_TERMS.contains(term))
                .collect(java.util.stream.Collectors.toSet());
        return matchingIntentSentences(question, documents, ACTION_SENTENCE, focusTerms);
    }

    /** Returns only source sentences that directly state a relevant decision or selection. */
    public static java.util.Optional<String> answerDecisionFromEvidence(String question, List<Document> documents) {
        java.util.Set<String> focusTerms = contentTerms(question).stream()
                .filter(term -> !DECISION_QUERY_TERMS.contains(term))
                .collect(java.util.stream.Collectors.toSet());
        return matchingIntentSentences(question, documents, DECISION_SENTENCE, focusTerms);
    }

    private static java.util.Optional<String> matchingIntentSentences(
            String question, List<Document> documents, Pattern intentPattern, java.util.Set<String> focusTerms) {
        java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        List<String> constraints = questionConstraints(question);
        for (Document document : documents) {
            for (String rawSentence : document.getText().split("(?<=[.!?])\\s+|\\R+")) {
                String sentence = rawSentence.trim();
                if (sentence.isBlank() || !intentPattern.matcher(sentence).find()
                        || !containsAllConstraints(sentence, constraints)) {
                    continue;
                }
                if (focusTerms.isEmpty()
                        || overlapCount(focusTerms, contentTerms(sentence)) > 0) {
                    matches.add(sentence);
                }
            }
        }
        if (matches.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(matches.stream().limit(5).collect(java.util.stream.Collectors.joining(" ")));
    }

    /** Answers a yes/no question only when one evidence sentence matches every substantive question term. */
    public static java.util.Optional<String> answerYesNoFromEvidence(String question, List<Document> documents) {
        java.util.Set<String> questionTerms = contentTerms(question);
        if (questionTerms.size() < 2) {
            return java.util.Optional.empty();
        }
        for (Document document : documents) {
            for (String rawSentence : document.getText().split("(?<=[.!?])\\s+|\\R+")) {
                for (String clause : clauses(rawSentence)) {
                    String sentence = clause.trim();
                    if (!sentence.isBlank()
                            && overlapCount(questionTerms, contentTerms(sentence)) == questionTerms.size()) {
                        return java.util.Optional.of((NEGATION.matcher(sentence).find() ? "No. " : "Yes. ")
                                + capitalize(sentence));
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** Answers a relation question only when a single sentence names its target and states a relation. */
    public static java.util.Optional<String> answerRelationFromEvidence(
            String requiredSubject, List<Document> documents) {
        if (requiredSubject == null || requiredSubject.isBlank()) {
            return java.util.Optional.empty();
        }
        for (Document document : documents) {
            for (String rawSentence : document.getText().split("(?<=[.!?])\\s+|\\R+")) {
                for (String clause : clauses(rawSentence)) {
                    String sentence = clause.trim();
                    if (containsTerm(sentence, requiredSubject) && RELATION_SENTENCE.matcher(sentence).find()) {
                        return java.util.Optional.of((NEGATION.matcher(sentence).find() ? "No. " : "Yes. ")
                                + capitalize(sentence));
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static String[] clauses(String sentence) {
        return sentence.split("(?iu)\\s+(?:but|however|whereas|although)\\s+|[,;:]");
    }

    private static String capitalize(String sentence) {
        return sentence.isEmpty() ? sentence : Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /** Rejects quotes that do not address the question or omit an explicitly requested date/value. */
    public static boolean evidenceQuotesAddressQuestion(String question, List<String> quotes) {
        return evidenceQuotesAddressQuestion(question, quotes, List.of(question));
    }

    /** Checks a source quote against the original question and any generic retrieval rewrites. */
    public static boolean evidenceQuotesAddressQuestion(
            String question, List<String> quotes, List<String> searchQueries) {
        if (!evidenceQuotesRespectQuestionConstraints(question, quotes)) {
            return false;
        }
        String quotedText = quotes.stream()
                .filter(java.util.Objects::nonNull)
                .map(QnaEvidenceSupport::normalizeEvidenceText)
                .collect(java.util.stream.Collectors.joining(" "));
        List<String> queries = searchQueries == null || searchQueries.isEmpty()
                ? List.of(question) : searchQueries;
        return queries.stream()
                .map(QnaEvidenceSupport::contentTerms)
                .filter(terms -> !terms.isEmpty())
                .anyMatch(terms -> {
                    int overlap = overlapCount(terms, contentTerms(quotedText));
                    int requiredOverlap = Math.max(1, (terms.size() + 2) / 3);
                    return overlap >= requiredOverlap;
                });
    }

    /** Checks explicit dates and numeric constraints without requiring lexical overlap for synthesis queries. */
    public static boolean evidenceQuotesRespectQuestionConstraints(String question, List<String> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return false;
        }
        String quotedText = quotes.stream()
                .filter(java.util.Objects::nonNull)
                .map(QnaEvidenceSupport::normalizeEvidenceText)
                .collect(java.util.stream.Collectors.joining(" "));
        return containsAllConstraints(quotedText, questionConstraints(question));
    }

    /** Keeps API citations limited to chunks that contain the supporting sentence or quote. */
    public static List<Document> documentsContainingEvidence(List<String> evidenceTexts, List<Document> documents) {
        if (evidenceTexts == null || evidenceTexts.isEmpty() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> fragments = evidenceTexts.stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(text -> java.util.Arrays.stream(text
                        .replaceFirst("(?iu)^\\s*(?:yes|no)\\.\\s*", "")
                        .split("(?<=[.!?])\\s+|;\\s*")))
                .map(QnaEvidenceSupport::normalizeEvidenceText)
                .filter(fragment -> fragment.split("\\s+").length >= 2)
                .toList();
        if (fragments.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .filter(document -> {
                    String documentText = normalizeEvidenceText(document.getText());
                    return fragments.stream().anyMatch(documentText::contains);
                })
                .toList();
    }

    private static List<String> questionConstraints(String question) {
        return QUESTION_CONSTRAINT.matcher(question == null ? "" : question).results()
                .map(match -> normalizeEvidenceText(match.group()))
                .distinct()
                .toList();
    }

    private static boolean containsAllConstraints(String text, List<String> constraints) {
        String normalizedText = normalizeEvidenceText(text);
        return constraints.stream().allMatch(normalizedText::contains);
    }

    private static java.util.Set<String> contentTerms(String text) {
        return java.util.Arrays.stream(normalizeEvidenceText(text).split("\\s+"))
                .filter(term -> !term.isBlank() && !QUESTION_FILLER_WORDS.contains(term)
                        && !QUESTION_GENERIC_TERMS.contains(term))
                .map(QnaEvidenceSupport::stem)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String stem(String term) {
        if (term.length() > 5 && term.endsWith("ing")) {
            return term.substring(0, term.length() - 3);
        }
        if (term.length() > 4 && term.endsWith("ed")) {
            return term.substring(0, term.length() - 2);
        }
        if (term.length() > 4 && term.endsWith("e")) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static int overlapCount(java.util.Set<String> left, java.util.Set<String> right) {
        return (int) left.stream().filter(right::contains).count();
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

    /** Replaces a bare model answer with its complete supporting sentence when available. */
    public static String completeFragmentAnswer(String answer, List<String> evidenceQuotes) {
        if (answer == null || evidenceQuotes == null || evidenceQuotes.isEmpty()
                || answer.trim().split("\\s+").length > 2) {
            return answer;
        }
        String normalizedAnswer = normalizeEvidenceText(answer);
        for (String quote : evidenceQuotes) {
            if (quote == null || quote.isBlank()) {
                continue;
            }
            String normalizedQuote = normalizeEvidenceText(quote);
            if (normalizedQuote.contains(normalizedAnswer)
                    && quote.trim().length() > answer.trim().length()) {
                return quote.trim();
            }
        }
        return answer;
    }

    public static boolean containsTerm(String text, String term) {
        return Pattern.compile("(?iu)\\b" + Pattern.quote(term) + "\\b")
                .matcher(text == null ? "" : text)
                .find();
    }

    public static String meetingIdFilter(String meetingId) {
        String pipelineFilter = "pipeline == '" + SathiConstants.CHAT_PIPELINE
                + "' && memory_type == 'transcript'";
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
