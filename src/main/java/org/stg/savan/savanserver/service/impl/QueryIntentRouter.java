package org.stg.savan.savanserver.service.impl;

import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

final class QueryIntentRouter {

    private static final Pattern ACTION_ITEM = Pattern.compile(
            "\\b(to[ -]?do|action items?|tasks?|next steps?|follow[ -]?ups?|"
                    + "on my plate|what should i (?:do|get done)|what do i need to do|"
                    + "what needs to be done|what can (?:i|we) do next|what do i do next|"
                    + "what is (?:my )?(?:next step|next action|next task)|"
                    + "what am i responsible for|what is assigned to me|"
                    + "who is responsible for|who owns)\\b");
    private static final Pattern DECISION = Pattern.compile(
            "\\b(decisions?|decide|decides|decided|deciding|agree(?:d)?|"
                    + "what did we choose|chosen approach)\\b|"
                    + "\\bwhere\\b.*\\b(?:generation|reasoning|processing|inference|calculation)\\b"
                    + ".*\\b(?:happen|stay|remain|run|take place)\\b");
    private static final Pattern FUTURE_PLAN = Pattern.compile(
            "\\b(future plans?|upcoming|later|plan|plans|planned|planning|"
                    + "milestones?|roadmap|what happens next)\\b");
    private static final Pattern INCIDENT_IMPACT = Pattern.compile(
            "(?is)(?=.*\\b(?:outage|incident|disruption|downtime|failure)\\b)"
                    + "(?=.*\\b(?:affect|affected|impact|impacted|production|prod|customer-facing|live)\\b).*");

    private QueryIntentRouter() {
    }

    static PlannedQuery plan(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        if (INCIDENT_IMPACT.matcher(normalized).matches()) {
            return new PlannedQuery("INCIDENT_IMPACT", List.of(
                    question,
                    "Was production impacted by the incident or outage?",
                    "Did the incident affect live or customer-facing systems? Was production unaffected?"));
        }
        if (ACTION_ITEM.matcher(normalized).find()) {
            return expanded("ACTION_ITEM", question, "Find assigned tasks, to-dos, next steps, follow-ups, "
                    + "responsibilities, what I or we should get done, task owners, and deadlines.");
        }
        if (DECISION.matcher(normalized).find()) {
            return expanded("DECISION", question, "Find decisions, agreements, chosen approaches, conclusions, "
                    + "and architecture choices about where processing, reasoning, or answer generation will run.");
        }
        if (FUTURE_PLAN.matcher(normalized).find()) {
            return expanded("FUTURE_PLAN", question, "Find future plans, upcoming activities, planned work, "
                    + "next milestones, and roadmap.");
        }
        return new PlannedQuery("TRANSCRIPT", List.of(question));
    }

    private static PlannedQuery expanded(String intent, String question, String expansion) {
        return new PlannedQuery(intent, List.of(question, question + "\n" + expansion));
    }

    record PlannedQuery(String intent, List<String> searchQueries) {
    }
}
