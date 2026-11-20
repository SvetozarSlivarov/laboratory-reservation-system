package com.labreservation.accounts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {
    Optional<OAuthAccount> findByProviderAndSubject(String provider, String subject);
}
