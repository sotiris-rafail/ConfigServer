package com.foundry.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class Dtos {
    private Dtos() {
    }

    public record LoginRequest(@NotBlank(message = "Enter your username.") String username,
                               @NotBlank(message = "Enter your password.") String password) {
    }

    public record Me(String username) {
    }

    /** How an application proves who it is and asks for a token. */
    public record TokenRequest(@NotBlank(message = "clientId is required.") String clientId,
                               @NotBlank(message = "clientSecret is required.") String clientSecret) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> apps) {
    }

    public record EntryRequest(
        @NotBlank(message = "Application is required.")
        @Size(max = 50, message = "Application name is too long.")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "Application may only use letters, numbers, - and _.")
        String app,
        @Size(max = 50, message = "Profile name is too long.")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "Profile may only use letters, numbers, - and _.")
        String profile,
        @NotBlank(message = "Key is required.")
        @Size(max = 200, message = "Key is too long.")
        @Pattern(regexp = "[A-Za-z0-9._\\[\\]-]*", message = "Key may only use letters, numbers, . - _ [ ].")
        String key,
        @Size(max = 4000, message = "Value is too long.") String value,
        boolean secret,
        @Size(max = 300, message = "Note is too long.") String description) {
    }

    public record EntryView(Long id, String app, String profile, String key, String value, boolean secret,
                            String description, Instant updatedAt, String updatedBy) {
    }

    /** What a client receives: the merged settings for one application. */
    public record Resolved(String app, List<String> profiles, Map<String, String> properties) {
    }

    /** One row of the console's preview: the winning value and where it came from. */
    public record PreviewRow(String key, String value, boolean secret, String source) {
    }

    public record AppsView(List<String> apps, List<String> profiles) {
    }

    public record ApiError(String message) {
    }
}
