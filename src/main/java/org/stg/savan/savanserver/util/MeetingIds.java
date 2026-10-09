package org.stg.savan.savanserver.util;

import org.stg.savan.savanserver.constants.SathiConstants;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Creates fallback meeting IDs and validates IDs supplied by clients. */
public final class MeetingIds {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private MeetingIds() {
    }

    public static String createOrPreserve(String providedMeetingId) {
        if (providedMeetingId == null || providedMeetingId.isBlank()) {
            return SathiConstants.MEETING_ID_PREFIX + TIMESTAMP_FORMAT.format(Instant.now())
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return validateAndNormalize(providedMeetingId);
    }

    public static String normalizeOptional(String meetingId) {
        return meetingId == null || meetingId.isBlank() ? null : validateAndNormalize(meetingId);
    }

    private static String validateAndNormalize(String meetingId) {
        String normalized = meetingId.trim();
        if (!SathiConstants.MEETING_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "meeting_id must be 1-128 characters using letters, digits, dots, underscores, colons, or hyphens");
        }
        return normalized;
    }
}
