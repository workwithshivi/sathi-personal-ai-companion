package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryIntentRouterTest {

    @Test
    void mapsActionItemParaphrasesToOneIntent() {
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is the to-do?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What can we do next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What can I do next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What should I get done next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is my next task?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What am I responsible for?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What should I follow up on?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is on my plate?").intent());
    }

    @Test
    void routesDecisionAndFutureQuestions() {
        assertEquals("DECISION", QueryIntentRouter.plan("What did we agree about Gemma?").intent());
        assertEquals("DECISION", QueryIntentRouter.plan("Where did the team decide final reasoning should happen?").intent());
        assertEquals("DECISION", QueryIntentRouter.plan("Where will final answer generation happen?").intent());
        assertEquals("DECISION", QueryIntentRouter.plan("What did the team decide about migration?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What upcoming work did we plan?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What is planned before the final demo?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What is the next milestone?").intent());
        assertEquals("TRANSCRIPT", QueryIntentRouter.plan("What did we discuss about Solid?").intent());
    }

    @Test
    void expandsMissedQuestionPhrasingsWithSearchConcepts() {
        var actionQuery = QueryIntentRouter.plan("What should I get done next?").searchQueries().get(1);
        var decisionQuery = QueryIntentRouter.plan("Where will final answer generation happen?").searchQueries().get(1);
        var futureQuery = QueryIntentRouter.plan("What is the next milestone?").searchQueries().get(1);

        assertTrue(actionQuery.contains("task owners"));
        assertTrue(decisionQuery.contains("where processing, reasoning, or answer generation"));
        assertTrue(futureQuery.contains("next milestones, and roadmap"));
    }

    @Test
    void expandsOutageImpactQuestionsToIncidentAndProductionPhrasings() {
        var planned = QueryIntentRouter.plan("Did the outage affect production?");

        assertEquals("INCIDENT_IMPACT", planned.intent());
        assertEquals(3, planned.searchQueries().size());
        assertTrue(planned.searchQueries().get(1).contains("incident or outage"));
        assertTrue(planned.searchQueries().get(2).contains("production unaffected"));
    }
}
