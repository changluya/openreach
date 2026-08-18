package io.github.changlu.openreach.web;

import io.github.changlu.openreach.common.UpstreamHttpException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void exposesStructuredUpstreamHttpMetadataWithoutBreakingLegacyErrorCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.upstream(new UpstreamHttpException(412, false));
        Map<String, Object> body = response.getBody();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_ERROR", body.get("code"));
        assertEquals("HTTP_412", body.get("failureType"));
        assertEquals(412, body.get("upstreamStatus"));
        assertEquals(false, body.get("retryable"));
    }
}
