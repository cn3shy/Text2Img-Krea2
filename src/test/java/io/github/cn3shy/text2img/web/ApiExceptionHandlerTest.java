package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void clientAbortReturnsNoBody() {
        assertNull(handler.handleUnexpected(new AsyncRequestNotUsableException("broken pipe")));
    }

    @Test
    void wrappedClientAbortReturnsNoBody() {
        assertNull(handler.handleUnexpected(new RuntimeException(new AsyncRequestNotUsableException("x"))));
    }

    @Test
    void unexpectedExceptionStillReturns500() {
        ResponseEntity<?> response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertNotNull(response);
        assertEquals(500, response.getStatusCode().value());
    }
}
