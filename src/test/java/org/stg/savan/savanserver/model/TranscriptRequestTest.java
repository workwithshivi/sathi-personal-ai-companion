package org.stg.savan.savanserver.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptRequestTest {

    @Test
    void combinesNonBlankSegmentsInOrder() {
        TranscriptRequest request = new TranscriptRequest(
                Arrays.asList(" First segment. ", "", null, "Second segment."),
                "raspberry-pi",
                "meeting-20261009");

        assertEquals("First segment.\nSecond segment.", request.combinedText());
    }

    @Test
    void nullSegmentsBecomeEmptyTextForControllerValidation() {
        assertEquals("", new TranscriptRequest(null, null, null).combinedText());
        assertEquals("", new TranscriptRequest(List.of(" ", ""), null, null).combinedText());
    }
}
