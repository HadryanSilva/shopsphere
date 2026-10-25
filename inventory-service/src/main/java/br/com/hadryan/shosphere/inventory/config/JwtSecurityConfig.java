package br.com.hadryan.shosphere.inventory.config;

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
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var scopes = new JwtGrantedAuthoritiesConverter();
            Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
            if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                    && realmAccess.get("roles") instanceof Collection<?> roles) {
                roles.stream().filter(String.class::isInstance).map(String.class::cast)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).forEach(authorities::add);
            }
            return authorities;
        });
        return converter;
    }
}
