package br.com.hadryan.shopsphere.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Configuration
public class JwtSecurityConfig {

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {

            JwtGrantedAuthoritiesConverter scopes =
                    new JwtGrantedAuthoritiesConverter();

            Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));

            Map<String, Object> realmAccess =
                    jwt.getClaimAsMap("realm_access");

            if (realmAccess != null) {
                Object roles = realmAccess.get("roles");

                if (roles instanceof Collection<?> roleNames) {
                    roleNames.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(role -> new SimpleGrantedAuthority(
                                    "ROLE_" + role
                            ))
                            .forEach(authorities::add);
                }
            }

            return authorities;
        });

        return converter;
    }
}

