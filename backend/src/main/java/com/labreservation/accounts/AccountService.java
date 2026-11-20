package com.labreservation.accounts;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private final UserRepository users;
    private final OAuthAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AccountService(UserRepository users, OAuthAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Account register(String email, String password) {
        try {
            return Account.from(users.saveAndFlush(new User(email, passwordEncoder.encode(password), UserRole.USER)));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
    }

    @Transactional
    public Account googleSignIn(String subject, String email, boolean verified, Long signedInUserId) {
        if (!verified || email == null || email.isBlank() || email.length() > 254
                || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Google must provide a verified email and valid identity");
        }

        var identity = accounts.findByProviderAndSubject("google", subject);
        if (identity.isPresent()) {
            User owner = identity.get().getUser();
            if (signedInUserId != null && !signedInUserId.equals(owner.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Google identity belongs to another account");
            }
            return Account.from(owner);
        }

        var existing = users.findByEmail(User.normalizeEmail(email));
        User owner;
        if (signedInUserId != null) {
            owner = existing.filter(user -> signedInUserId.equals(user.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "The verified Google email must match your signed-in account"));
        } else if (existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sign in to your existing account before linking Google");
        } else {
            owner = new User(email, null, UserRole.USER);
        }

        try {
            if (owner.getId() == null) {
                owner = users.saveAndFlush(owner);
            }
            accounts.saveAndFlush(new OAuthAccount(owner, "google", subject));
            return Account.from(owner);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists or is already linked");
        }
    }

    @Transactional(readOnly = true)
    public Account account(long id) {
        return users.findById(id).map(Account::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    public record Account(Long id, String email, UserRole role) {
        static Account from(User user) {
            return new Account(user.getId(), user.getEmail(), user.getRole());
        }
    }
}
