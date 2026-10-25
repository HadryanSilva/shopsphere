package br.com.hadryan.shosphere.inventory.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter, JsonMapper mapper) {
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        var deniedHandler = new BearerTokenAccessDeniedHandler();
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/inventory/items",
                                "/api/v1/inventory/items/*/adjustments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/items/batch",
                                "/api/v1/inventory/items/*",
                                "/api/v1/inventory/items/*/adjustments/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/internal/api/v1/inventory/reservations/*")
                                .access(orderServiceAccess())
                        .requestMatchers(HttpMethod.GET, "/internal/api/v1/inventory/reservations/*")
                                .access(orderServiceAccess())
                        .requestMatchers(HttpMethod.POST, "/internal/api/v1/inventory/reservations/*/confirm",
                                "/internal/api/v1/inventory/reservations/*/release").access(orderServiceAccess())
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            entryPoint.commence(request, response, exception);
                            writeProblem(request, response, mapper, HttpStatus.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            deniedHandler.handle(request, response, exception);
                            writeProblem(request, response, mapper, HttpStatus.FORBIDDEN);
                        }))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            entryPoint.commence(request, response, exception);
                            writeProblem(request, response, mapper, HttpStatus.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            deniedHandler.handle(request, response, exception);
                            writeProblem(request, response, mapper, HttpStatus.FORBIDDEN);
                        }))
                .build();
    }

    private AuthorizationManager<RequestAuthorizationContext> orderServiceAccess() {
        return (authentication, context) -> {
            var principal = authentication.get();
            return new AuthorizationDecision(principal instanceof JwtAuthenticationToken jwt
                    && "shopsphere-order".equals(jwt.getToken().getClaim("azp"))
                    && jwt.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_INVENTORY_SERVICE")));
        };
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response,
                              JsonMapper mapper, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", status.getReasonPhrase(),
                "status", status.value(), "detail", status == HttpStatus.UNAUTHORIZED
                        ? "Authentication is required." : "Access is denied.",
                "instance", request.getRequestURI(), "errors", List.of()));
    }
}
