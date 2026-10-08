package org.stg.savan.savanserver.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryIntentRouterTest {

    @Test
    void mapsActionItemParaphrasesToOneIntent() {
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is the to-do?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What can we do next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What can I do next?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What should I follow up on?").intent());
        assertEquals("ACTION_ITEM", QueryIntentRouter.plan("What is on my plate?").intent());
    }

    @Test
    void routesDecisionAndFutureQuestions() {
        assertEquals("DECISION", QueryIntentRouter.plan("What did we agree about Gemma?").intent());
        assertEquals("FUTURE_PLAN", QueryIntentRouter.plan("What upcoming work did we plan?").intent());
        assertEquals("TRANSCRIPT", QueryIntentRouter.plan("What did we discuss about Solid?").intent());
    }
}
