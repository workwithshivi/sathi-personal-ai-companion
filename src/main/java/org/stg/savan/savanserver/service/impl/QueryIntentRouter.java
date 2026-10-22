package org.stg.savan.savanserver.service.impl;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plans broad retrieval variants from question shape without assuming a domain or vocabulary. */
final class QueryIntentRouter {

    private static final Pattern ACTION_ITEM = Pattern.compile(
            "\\b(to[ -]?do|action items?|tasks?|next steps?|follow[ -]?ups?|"
                    + "on my plate|what should i (?:do|get done)|what do i need to do|"
                    + "what (?:can|could|should|must) (?:[\\p{L}\\p{N}_'-]+\\s+){0,3}do next|"
                    + "who (?:will|would|can|should)\\b|"
                    + "what needs to be done|what can (?:i|we) do next|what do i do next|"
                    + "what is (?:my )?(?:next step|next action|next task)|"
                    + "what am i responsible for|what is assigned to me|"
                    + "who is responsible for|who owns)\\b");
    private static final Pattern DECISION = Pattern.compile(
            "\\b(decisions?|decide|decides|decided|deciding|agree(?:d)?|"
                    + "choose|chose|chosen|select|selects|selected|selecting|trade[ -]?off)\\b");
    private static final Pattern FUTURE_PLAN = Pattern.compile(
            "\\b(future plans?|future scope|further plans?|further work|"
                    + "what should (?:(?:the|our|my|your|their)\\s+)?"
                    + "(?:team|group|we|i|[\\p{L}][\\p{L}\\p{N}_'-]*)\\s+"
                    + "(?:work on|focus on|take on|prioritize)\\s+(?:next|now)|"
                    + "what (?:will|could|would) be the improvement|improvements? (?:planned|discussed|needed)|"
                    + "upcoming|later|plan|plans|planned|planning|"
                    + "milestones?|roadmap|what happens next|next steps?|"
                    + "scheduled|timeline|deadline|target date)\\b");
    private static final Pattern DEFINITION_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:what\\s+is|what's|who\\s+is|define|explain|describe)\\s+"
                    + "(?:the\\s+)?(.+?)\\s*[?.!]*\\s*$");
    private static final Pattern ISSUE_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:what(?:'s|\\s+is)\\s+(?:currently\\s+)?(?:wrong|broken|not\\s+working)\\s+with|"
                    + "what(?:'s|\\s+is)\\s+the\\s+(?:issue|problem)\\s+with)\\s+(.+?)\\s*[?.!]*\\s*$");
    private static final Pattern YES_NO_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:did|do|does|is|are|was|were|has|have|had|can|could|will|would)\\b.*[?.!]?$");
    private static final Pattern RELATION_QUESTION = Pattern.compile(
            "(?iu)\\b(?:affect(?:s|ed)?|impact(?:s|ed)?|influence(?:s|d)?|"
                    + "change(?:s|d)?|alter(?:s|ed)?|delay(?:s|ed)?|block(?:s|ed)?|"
                    + "prevent(?:s|ed)?|disrupt(?:s|ed)?|cause(?:s|d)?|"
                    + "increase(?:s|d)?|decrease(?:s|d)?|improve(?:s|d)?|reduce(?:s|d)?)"
                    + "\\s+(?:the\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N}-]*(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}-]*){0,2})");
    private static final Pattern PASSIVE_RELATION_QUESTION = Pattern.compile(
            "(?iu)\\b(?:the\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)\\s+"
                    + "(?:(?:get|got|be|was|were|is|are)\\s+)?"
                    + "(?:affected|impacted|influenced|changed|altered|delayed|blocked|"
                    + "prevented|disrupted|caused|increased|decreased|improved|reduced)\\b");
    private static final Set<String> RELATION_VERBS = Set.of(
            "affect", "affects", "affected", "impact", "impacts", "impacted", "influence", "influences",
            "influenced", "change", "changes", "changed", "alter", "alters", "altered", "delay", "delays",
            "delayed", "block", "blocks", "blocked", "prevent", "prevents", "prevented", "disrupt", "disrupts",
            "disrupted", "cause", "causes", "caused", "increase", "increases", "increased", "decrease",
            "decreases", "decreased", "improve", "improves", "improved", "reduce", "reduces", "reduced");
    private static final Set<String> RELATION_SUBJECT_STOP_WORDS = Set.of(
            "after", "and", "before", "because", "by", "during", "for", "from", "in", "into",
            "of", "on", "or", "through", "to", "under", "until", "when", "while", "with");
    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "a", "an", "are", "about", "can", "did", "do", "does", "for", "from", "had", "has", "have",
            "how", "i", "in", "is", "it", "me", "my", "of", "on", "the", "this", "that", "to",
            "regarding", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will", "with",
            "would", "you", "your");

    private QueryIntentRouter() {
    }

    static PlannedQuery plan(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);

        String relationTarget = relationTarget(question);
        if (relationTarget != null) {
            return new PlannedQuery("RELATION", List.of(
                    question,
                    question + " Find transcript evidence that confirms or denies this relationship.",
                    "Find transcript statements about " + relationTarget
                            + ", including relevant decisions, changes, reasons, and outcomes."),
                    relationTarget);
        }
        if (YES_NO_QUESTION.matcher(question).matches()) {
            return new PlannedQuery("YES_NO", List.of(
                    question,
                    "Find explicit transcript statements that answer this yes-or-no question, including "
                            + "positive or negative wording and related terms.",
                    question + " Look for synonyms, outcomes, and explicit confirmation or denial.",
                    "Transcript evidence about " + questionFocus(question)
                            + "; stated outcome, confirmation, denial, and whether it occurred."), null);
        }
        if (ACTION_ITEM.matcher(normalized).find()) {
            return expanded("ACTION_ITEM", question,
                    "Find mentioned tasks, assigned responsibilities, owners, follow-ups, and due dates.");
        }
        if (DECISION.matcher(normalized).find()) {
            String focus = decisionFocus(question);
            return new PlannedQuery("DECISION", List.of(
                    question,
                    question + "\nFind relevant choices, agreements, rejected options, and reasons given for decisions.",
                    "What decisions, choices, agreements, or selected options were recorded about " + focus + "?",
                    "What did the group decide regarding " + focus + "?"), null);
        }
        if (FUTURE_PLAN.matcher(normalized).find()) {
            return expanded("FUTURE_PLAN", question,
                    "Find relevant future scope, planned and upcoming work, next tasks, proposed improvements, "
                            + "priorities, timelines, milestones, and deadlines.");
        }
        Matcher issue = ISSUE_QUESTION.matcher(question);
        if (issue.matches()) {
            String subject = issue.group(1).trim();
            return new PlannedQuery("ISSUE", List.of(
                    question,
                    "Find the issue, problem, fault, malfunction, failure, or item reported as not working with "
                            + subject + ".",
                    subject,
                    subject + " issue problem fault malfunction failure not working repair"), null);
        }
        Matcher definition = DEFINITION_QUESTION.matcher(question);
        if (definition.matches()) {
            String entity = definition.group(1).replaceAll("[?.!]+$", "").trim();
            String normalizedEntity = normalizeSpelling(entity);
            return new PlannedQuery("DEFINITION", List.of(
                    question,
                    "Definition, meaning, description, purpose, role, and function of " + entity,
                    "What is " + normalizedEntity + "? " + entity + " definition and description"), null);
        }
        return new PlannedQuery("TRANSCRIPT", List.of(question), null);
    }

    private static PlannedQuery expanded(String intent, String question, String expansion) {
        return new PlannedQuery(intent, List.of(question, question + "\n" + expansion), null);
    }

    private static String relationTarget(String question) {
        Matcher passive = PASSIVE_RELATION_QUESTION.matcher(question);
        if (passive.find()) {
            String target = cleanSubject(passive.group(1));
            if (target.length() >= 3 && !isGenericRelationTarget(target)) {
                return target;
            }
        }

        Matcher active = RELATION_QUESTION.matcher(question);
        String target = null;
        int searchStart = 0;
        while (searchStart < question.length() && active.find(searchStart)) {
            String candidate = cleanSubject(active.group(1));
            if (!RELATION_VERBS.contains(candidate.toLowerCase(Locale.ROOT))) {
                target = candidate;
            }
            searchStart = active.start() + 1;
        }
        if (target == null || target.length() < 3 || isGenericRelationTarget(target)) {
            return null;
        }
        return target;
    }

    private static String cleanSubject(String value) {
        return java.util.Arrays.stream(value.split("\\s+"))
                .takeWhile(word -> !RELATION_SUBJECT_STOP_WORDS.contains(word.toLowerCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String questionFocus(String question) {
        return java.util.Arrays.stream(question.toLowerCase(Locale.ROOT).replaceAll("[?.!]", "").split("\\s+"))
                .filter(word -> !QUESTION_STOP_WORDS.contains(word))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String decisionFocus(String question) {
        String focus = questionFocus(question).replaceAll(
                "(?iu)\\b(?:team|group|meeting|decide|decides|decided|decision|decisions|choose|chose|chosen|"
                        + "select|selected|selecting|option|options|agree|agreed|agreement)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        return focus.isBlank() ? "the meeting" : focus;
    }

    private static boolean isGenericRelationTarget(String value) {
        return List.of("thing", "things", "anything", "everything", "something", "it", "this", "that")
                .contains(value.toLowerCase(Locale.ROOT));
    }

    private static String normalizeSpelling(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("([aeiou])\\1+", "$1");
    }

    record PlannedQuery(String intent, List<String> searchQueries, String requiredSubject) {
    }
}
