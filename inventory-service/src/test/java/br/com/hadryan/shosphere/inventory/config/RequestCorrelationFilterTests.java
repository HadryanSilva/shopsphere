package br.com.hadryan.shosphere.inventory.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTests {
    @Test void correlatesAllStatusesWithoutLoggingRequestData() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int status : new int[]{201, 401, 403, 409, 502, 503}) {
                var request = new MockHttpServletRequest("POST", "/sensitive-path");
                request.addHeader("Authorization", "Bearer sensitive-token");
                request.addHeader("X-Request-ID", "sensitive-correlation");
                request.setQueryString("secret=sensitive-query");
                request.setContent("sensitive-body".getBytes());
                var response = new MockHttpServletResponse();
                new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                    assertEquals(response.getHeader("X-Request-ID"), MDC.get("requestId"));
                    response.setStatus(status);
                });
                assertNotNull(UUID.fromString(response.getHeader("X-Request-ID")));
                assertEquals(status, response.getStatus());
                assertNull(MDC.get("requestId"));
                var event = logs.list.getLast();
                assertTrue(event.getFormattedMessage().contains(response.getHeader("X-Request-ID")));
                assertTrue(event.getFormattedMessage().contains("status=" + status));
                assertFalse(event.getFormattedMessage().contains("sensitive"));
                assertEquals(status == 409 || status >= 500 ? "WARN" : "INFO", event.getLevel().toString());
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test void restoresMdcEvenWhenTheChainThrows() {
        MDC.put("requestId", "outer-context");
        MDC.put("other", "preserved");
        try {
            var response = new MockHttpServletResponse();
            assertThrows(ServletException.class, () -> new RequestCorrelationFilter().doFilter(
                    new MockHttpServletRequest(), response, (req, res) -> {
                        assertNotEquals("outer-context", MDC.get("requestId"));
                        throw new ServletException("sensitive-error");
                    }));
            assertEquals("outer-context", MDC.get("requestId"));
            assertEquals("preserved", MDC.get("other"));
        } finally {
            MDC.remove("requestId");
            MDC.remove("other");
        }
    }
}
