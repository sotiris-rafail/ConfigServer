package com.foundry.config.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** One setting: which application it is for, which profile, the key and its value. */
@Entity
@Table(name = "config_entry",
    uniqueConstraints = @UniqueConstraint(columnNames = {"app_name", "profile_name", "prop_key"}))
public class ConfigEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_name", nullable = false, length = 50)
    private String appName;

    @Column(name = "profile_name", nullable = false, length = 50)
    private String profileName;

    @Column(name = "prop_key", nullable = false, length = 200)
    private String propKey;

    @Column(name = "prop_value", nullable = false, length = 4000)
    private String propValue = "";

    @Column(nullable = false)
    private boolean secret;

    @Column(length = 300)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public Long getId() { return id; }
    public String getAppName() { return appName; }
    public void setAppName(String v) { this.appName = v; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String v) { this.profileName = v; }
    public String getPropKey() { return propKey; }
    public void setPropKey(String v) { this.propKey = v; }
    public String getPropValue() { return propValue; }
    public void setPropValue(String v) { this.propValue = v == null ? "" : v; }
    public boolean isSecret() { return secret; }
    public void setSecret(boolean v) { this.secret = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
