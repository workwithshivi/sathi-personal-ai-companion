package org.stg.savan.savanserver.util;

import org.slf4j.Logger;

/** Small reusable wrappers for consistent, optionally gated API diagnostics. */
public final class ApiRequestLogSupport {

    private ApiRequestLogSupport() {
    }

    public static void info(Logger log, String message, Object... arguments) {
        log.info(message, arguments);
    }

    public static void debug(Logger log, String message, Object... arguments) {
        log.debug(message, arguments);
    }

    public static void apiExchange(Logger log, String endpoint, int status, Object request, Object response) {
        log.info("API exchange | endpoint={} | status={} | request={} | response={}",
                endpoint, status, request, response);
    }

    public static void infoWhen(Logger log, boolean enabled, String message, Object... arguments) {
        if (enabled) {
            log.info(message, arguments);
        }
    }

    public static void debugWhen(Logger log, boolean enabled, String message, Object... arguments) {
        if (enabled) {
            log.debug(message, arguments);
        }
    }

    public static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
