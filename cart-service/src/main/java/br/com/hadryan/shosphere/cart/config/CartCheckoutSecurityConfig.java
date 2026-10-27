package br.com.hadryan.shosphere.cart.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class CartCheckoutSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain cartCheckoutSecurityFilterChain(HttpSecurity http, JwtDecoder decoder, JsonMapper mapper,
            @Value("${security.checkout.audience:shopsphere-api}") String audience) {
        Assert.hasText(audience, "Checkout audience must not be blank");
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        var deniedHandler = new BearerTokenAccessDeniedHandler();
        var internalEntryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, exception) -> {
            entryPoint.commence(request, response, exception);
            writeProblem(request, response, mapper, HttpStatus.UNAUTHORIZED);
        };
        var internalDeniedHandler = (org.springframework.security.web.access.AccessDeniedHandler) (request, response, exception) -> {
            deniedHandler.handle(request, response, exception);
            writeProblem(request, response, mapper, HttpStatus.FORBIDDEN);
        };
        return http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PUT, "/internal/api/v1/cart-checkouts/*").access(orderServiceAccess())
                        .requestMatchers(HttpMethod.GET, "/internal/api/v1/cart-checkouts/*").access(orderServiceAccess())
                        .requestMatchers(HttpMethod.POST, "/internal/api/v1/cart-checkouts/*/complete",
                                "/internal/api/v1/cart-checkouts/*/abort").access(orderServiceAccess())
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(internalEntryPoint)
                        .accessDeniedHandler(internalDeniedHandler))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(token -> {
                            // Keep Boot's signature/time/issuer validation; require audience only on this chain.
                            var decoded = decoder.decode(token);
                            if (decoded.getAudience() == null || !decoded.getAudience().contains(audience))
                                throw new BadJwtException("Invalid checkout audience");
                            return decoded;
                        }))
                        .authenticationEntryPoint(internalEntryPoint).accessDeniedHandler(internalDeniedHandler))
                .build();
    }

    private AuthorizationManager<RequestAuthorizationContext> orderServiceAccess() {
        return (authentication, context) -> {
            var principal = authentication.get();
            boolean allowed = principal instanceof JwtAuthenticationToken jwt
                    && "shopsphere-order".equals(jwt.getToken().getClaim("azp"))
                    && jwt.getToken().getClaim("realm_access") instanceof Map<?, ?> realm
                    && realm.get("roles") instanceof Collection<?> roles
                    && roles.contains("CART_CHECKOUT_SERVICE");
            return new AuthorizationDecision(allowed);
        };
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, JsonMapper mapper,
                              HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", status.getReasonPhrase(),
                "status", status.value(), "detail", status == HttpStatus.UNAUTHORIZED
                        ? "Authentication is required." : "Access is denied.",
                "instance", request.getRequestURI(), "errors", List.of()));
    }
}
