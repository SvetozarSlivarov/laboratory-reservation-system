package com.labreservation.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "oauth_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_oauth_provider_subject", columnNames = {"provider", "subject"}),
        @UniqueConstraint(name = "uk_oauth_user_provider", columnNames = {"user_id", "provider"})
})
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_oauth_user"))
    private User user;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 255)
    private String subject;


    protected OAuthAccount() {
    }

    public OAuthAccount(User user, String provider, String subject) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }
}
