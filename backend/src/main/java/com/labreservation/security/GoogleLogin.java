package com.labreservation.security;

import com.labreservation.accounts.AccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
class GoogleLogin {

    private final AccountService accounts;
    private final OidcUserService delegate = new OidcUserService();

    GoogleLogin(AccountService accounts) {
        this.accounts = accounts;
    }

    OidcUser loadUser(OidcUserRequest request) {
        var user = delegate.loadUser(request);
        var current = SecurityContextHolder.getContext().getAuthentication();
        Long signedInId = current == null || current instanceof AnonymousAuthenticationToken
                ? null : Long.valueOf(current.getName());
        try {
            var account = accounts.googleSignIn(user.getSubject(), user.getEmail(),
                    Boolean.TRUE.equals(user.getEmailVerified()), signedInId);
            return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_" + account.role())),
                    user.getIdToken()) {
                @Override
                public String getName() {
                    return account.id().toString();
                }
            };
        } catch (ResponseStatusException exception) {
            String code = exception.getStatusCode().value() == 409 ? "account_conflict" : "unverified_identity";
            throw new OAuth2AuthenticationException(new OAuth2Error(code, exception.getReason(), null));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.google.enabled", havingValue = "true")
    static class ClientConfiguration {

        @Bean
        ClientRegistrationRepository googleClients(
                @Value("${GOOGLE_CLIENT_ID}") String clientId,
                @Value("${GOOGLE_CLIENT_SECRET}") String clientSecret) {
            var registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .scope("openid", "email")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
