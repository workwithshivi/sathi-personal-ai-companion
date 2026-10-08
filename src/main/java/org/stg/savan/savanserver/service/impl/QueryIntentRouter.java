package org.stg.savan.savanserver.service.impl;

import java.util.Locale;
import java.util.regex.Pattern;

final class QueryIntentRouter {

    private static final Pattern ACTION_ITEM = Pattern.compile(
            "\\b(to[ -]?do|action items?|tasks?|next steps?|follow[ -]?ups?|"
                    + "on my plate|what should i do|what do i need to do|"
                    + "what can (?:i|we) do next|what do i do next)\\b");
    private static final Pattern DECISION = Pattern.compile(
            "\\b(decisions?|decided|agree(?:d)?|what did we choose|chosen approach)\\b");
    private static final Pattern FUTURE_PLAN = Pattern.compile(
            "\\b(future plans?|upcoming|later|what happens next|planned work)\\b");

    private QueryIntentRouter() {
    }

    static PlannedQuery plan(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        if (ACTION_ITEM.matcher(normalized).find()) {
            return new PlannedQuery("ACTION_ITEM", question + "\n"
                    + "Find assigned tasks, to-dos, next steps, follow-ups, responsibilities, and deadlines.");
        }
        if (DECISION.matcher(normalized).find()) {
            return new PlannedQuery("DECISION", question + "\n"
                    + "Find decisions, agreements, chosen approaches, and conclusions.");
        }
        if (FUTURE_PLAN.matcher(normalized).find()) {
            return new PlannedQuery("FUTURE_PLAN", question + "\n"
                    + "Find future plans, upcoming activities, and planned work.");
        }
        return new PlannedQuery("TRANSCRIPT", question);
    }

    record PlannedQuery(String intent, String searchText) {
    }
}
