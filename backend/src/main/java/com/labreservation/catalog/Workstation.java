package com.labreservation.catalog;

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
@Table(name = "workstations", uniqueConstraints = @UniqueConstraint(
        name = "uk_workstations_lab_code", columnNames = {"laboratory_id", "code"}))
public class Workstation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "laboratory_id", nullable = false, foreignKey = @ForeignKey(name = "fk_workstations_lab"))
    private Laboratory laboratory;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(length = 500)
    private String capabilities;

    @Column(nullable = false)
    private boolean enabled;


    protected Workstation() {
    }

    public Workstation(Laboratory laboratory, String code, String capabilities, boolean enabled) {
        this.laboratory = laboratory;
        this.code = code;
        this.capabilities = capabilities;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public Laboratory getLaboratory() {
        return laboratory;
    }

    public String getCode() {
        return code;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
