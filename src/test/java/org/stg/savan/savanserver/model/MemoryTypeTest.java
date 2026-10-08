package org.stg.savan.savanserver.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryTypeTest {

    @Test
    void exposesSupportedMemoryTypesAndMetadataValues() {
        assertArrayEquals(
                new MemoryType[]{
                        MemoryType.TRANSCRIPT,
                        MemoryType.ACTION_ITEM,
                        MemoryType.DECISION,
                        MemoryType.FUTURE_PLAN
                },
                MemoryType.values()
        );
        assertEquals("transcript", MemoryType.TRANSCRIPT.getMetadataValue());
        assertEquals("action_item", MemoryType.ACTION_ITEM.getMetadataValue());
        assertEquals("decision", MemoryType.DECISION.getMetadataValue());
        assertEquals("future_plan", MemoryType.FUTURE_PLAN.getMetadataValue());
    }
}
