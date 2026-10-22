package org.stg.savan.savanserver.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertNull;

class GroundedChatAnswerTest {

    @Test
    void deserializesNullOptionalFlagsFromStructuredModelOutput() throws Exception {
        GroundedChatAnswer answer = JsonMapper.builder().build().readValue("""
                {
                  "answer": "An answer grounded in the transcript.",
                  "evidence_quotes": ["The transcript statement."],
                  "supported_by_evidence": true,
                  "corpus_wide_request": null
                }
                """, GroundedChatAnswer.class);

        assertNull(answer.corpusWideRequest());
    }
}
