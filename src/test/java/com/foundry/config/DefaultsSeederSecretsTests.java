package com.foundry.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.repo.ConfigEntryRepository;
import com.foundry.config.service.DefaultsSeeder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The backend's password salt and email key are made once, are secret, and are never replaced. */
class DefaultsSeederSecretsTests {

    @Test
    void the_two_secrets_are_made_when_missing_even_on_an_existing_database() {
        ConfigEntryRepository repo = mock(ConfigEntryRepository.class);
        ConfigProps props = mock(ConfigProps.class);
        when(props.seedDefaults()).thenReturn(true);
        when(repo.count()).thenReturn(7L); // not an empty database
        when(repo.findByAppNameAndProfileNameAndPropKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        new DefaultsSeeder(repo, props).run(null);

        ArgumentCaptor<ConfigEntry> saved = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(repo, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        List<ConfigEntry> keys = saved.getAllValues().stream()
            .filter(e -> e.getPropKey().startsWith("gym.security.")).toList();
        assertThat(keys).extracting(ConfigEntry::getPropKey).containsExactlyInAnyOrder("gym.security.password-salt", "gym.security.email-key");
        assertThat(keys).allSatisfy(e -> {
            assertThat(e.isSecret()).isTrue();
            assertThat(e.getAppName()).isEqualTo("backend");
            assertThat(e.getPropValue()).hasSizeGreaterThanOrEqualTo(32);
        });
        assertThat(keys.get(0).getPropValue()).isNotEqualTo(keys.get(1).getPropValue());
    }

    @Test
    void existing_secrets_are_never_replaced() {
        ConfigEntryRepository repo = mock(ConfigEntryRepository.class);
        ConfigProps props = mock(ConfigProps.class);
        when(props.seedDefaults()).thenReturn(true);
        when(repo.count()).thenReturn(7L);
        when(repo.findByAppNameAndProfileNameAndPropKey(anyString(), anyString(), anyString())).thenReturn(Optional.of(new ConfigEntry()));

        new DefaultsSeeder(repo, props).run(null);

        verify(repo, never()).save(any());
    }

    @Test
    void every_email_setting_is_seeded_on_the_config_server_and_only_the_smtp_password_is_secret() {
        ConfigEntryRepository repo = mock(ConfigEntryRepository.class);
        ConfigProps props = mock(ConfigProps.class);
        when(props.seedDefaults()).thenReturn(true);
        when(repo.count()).thenReturn(7L);
        when(repo.findByAppNameAndProfileNameAndPropKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        new DefaultsSeeder(repo, props).run(null);

        ArgumentCaptor<ConfigEntry> saved = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(repo, org.mockito.Mockito.atLeast(1)).save(saved.capture());
        List<ConfigEntry> mailEntries = saved.getAllValues().stream()
            .filter(e -> e.getPropKey().startsWith("gym.mail.") || e.getPropKey().startsWith("spring.mail.")).toList();
        assertThat(mailEntries).extracting(ConfigEntry::getPropKey).contains("gym.mail.enabled", "gym.mail.from", "gym.mail.app-url",
            "gym.mail.gym-name", "gym.mail.currency", "gym.mail.scan-seconds", "gym.mail.send-seconds", "gym.mail.initial-delay-seconds",
            "gym.mail.reminder-hour", "gym.mail.news-window-days", "gym.mail.max-attempts", "gym.mail.batch-size",
            "spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password",
            "spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.enable");
        assertThat(mailEntries).allSatisfy(e -> assertThat(e.getAppName()).isEqualTo("backend"));
        assertThat(mailEntries).filteredOn(ConfigEntry::isSecret).extracting(ConfigEntry::getPropKey).containsExactly("spring.mail.password");
        assertThat(mailEntries).filteredOn(e -> e.getPropKey().equals("gym.mail.enabled")).extracting(ConfigEntry::getPropValue).containsExactly("false");
    }
}
