package com.labreservation.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Locale;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;


    protected User() {
    }

    public User(String email, String passwordHash, UserRole role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }
}
