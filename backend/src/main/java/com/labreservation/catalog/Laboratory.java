package com.labreservation.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "laboratories")
public class Laboratory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 255)
    private String location;

    @Column(nullable = false)
    private boolean enabled;


    protected Laboratory() {
    }

    public Laboratory(String name, String location, boolean enabled) {
        this.name = name;
        this.location = location;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
