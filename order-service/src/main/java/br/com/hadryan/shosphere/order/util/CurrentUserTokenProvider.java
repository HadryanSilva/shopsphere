package br.com.hadryan.shosphere.order.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserTokenProvider {

    public String getCurrentUserToken() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        JwtAuthenticationToken jwtAuthentication =
                (JwtAuthenticationToken) authentication;

        return "Bearer " + jwtAuthentication.getToken().getTokenValue();
    }

}
