package org.stg.savan.savanserver.model;

/** Classifies the kind of information stored in the memory vector store. */
public enum MemoryType {
    TRANSCRIPT("transcript"),
    ACTION_ITEM("action_item"),
    DECISION("decision"),
    FUTURE_PLAN("future_plan");

    private final String metadataValue;

    MemoryType(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String getMetadataValue() {
        return metadataValue;
    }
}
