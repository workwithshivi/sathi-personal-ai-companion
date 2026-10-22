package org.stg.savan.savanserver.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiRequestExceptionHandlerTest {

    @Test
    void returnsSafeBadRequestForUnreadableJson() {
        var response = new ApiRequestExceptionHandler()
                .handleUnreadableRequest();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Request body is invalid or has an incorrect field type",
                response.getBody().error());
    }
}
