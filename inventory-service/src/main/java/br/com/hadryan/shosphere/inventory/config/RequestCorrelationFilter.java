package br.com.hadryan.shosphere.inventory.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        var previous = MDC.get("requestId");
        // Generate locally: request headers are untrusted and may contain sensitive data.
        var requestId = UUID.randomUUID().toString();
        var started = System.nanoTime();
        boolean completed = false;
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                var status = response.getStatus();
                var elapsedMs = (System.nanoTime() - started) / 1_000_000;
                if (!completed || status == 409 || status >= 500) {
                    log.warn("Inventory HTTP request requestId={} status={} completed={} durationMs={}",
                            requestId, status, completed, elapsedMs);
                } else {
                    log.info("Inventory HTTP request requestId={} status={} completed={} durationMs={}",
                            requestId, status, completed, elapsedMs);
                }
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
            }
        }
    }
}
