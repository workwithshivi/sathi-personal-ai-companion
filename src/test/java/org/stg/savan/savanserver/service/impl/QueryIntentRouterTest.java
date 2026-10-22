package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryIntentRouterTest {

    @Test
    void mapsActionItemParaphrasesToOneIntent() {
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is the to-do?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What can we do next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What should I get done next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What am I responsible for?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What should I follow up on?").intent());
    }

    @Test
    void recognizesGeneralDecisionAndPlanningQuestions() {
        assertEquals("DECISION", QueryIntentRouter.plan("What did we agree about the migration?").intent());
        assertEquals("DECISION", QueryIntentRouter.plan("Which option did the group select?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What upcoming work did we plan?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What is the next milestone?").intent());
        assertEquals("TRANSCRIPT", QueryIntentRouter.plan("What did we discuss about the archive?").intent());
    }

    @Test
    void routesRelationQuestionsWithoutAssumingTheDomain() {
        var productionQuestion = QueryIntentRouter.plan("Did the outage affect production?");
        var shippingQuestion = QueryIntentRouter.plan("Did the supplier delay affect shipping?");
        var passiveQuestion = QueryIntentRouter.plan("Was the launch delayed by the supplier?");
        var multiWordTarget = QueryIntentRouter.plan("Did the team change the release date?");

        assertEquals("RELATION", productionQuestion.intent());
        assertEquals("production", productionQuestion.requiredSubject());
        assertEquals("shipping", shippingQuestion.requiredSubject());
        assertEquals("launch", passiveQuestion.requiredSubject());
        assertEquals("release date", multiWordTarget.requiredSubject());
        assertTrue(shippingQuestion.searchQueries().get(1).contains("confirms or denies this relationship"));
        assertTrue(multiWordTarget.searchQueries().get(2).contains("release date"));
    }

    @Test
    void recognizesGenericIssueQuestionsInsteadOfTreatingThemAsDefinitions() {
        var planned = QueryIntentRouter.plan("What is currently wrong with the meeting room equipment?");

        assertEquals("ISSUE", planned.intent());
        assertEquals(3, planned.searchQueries().size());
        assertTrue(planned.searchQueries().get(1).contains("fault, malfunction, failure"));
    }

    @Test
    void expandsDefinitionsForArbitraryNamedSubjects() {
        var planned = QueryIntentRouter.plan("What is the Atlas migration?");

        assertEquals("DEFINITION", planned.intent());
        assertEquals(3, planned.searchQueries().size());
        assertTrue(planned.searchQueries().get(1).contains("meaning, description, purpose, role, and function"));
        assertEquals("DEFINITION", QueryIntentRouter.plan("Who is the project owner?").intent());
    }
}
