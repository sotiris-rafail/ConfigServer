package com.foundry.config.service;

import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.repo.ConfigEntryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** At start, encrypts secret settings that were saved before encryption existed (or just seeded). Safe to run every time. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SecretMigrator implements ApplicationRunner {

    private final ConfigEntryRepository repo;
    private final SecretCrypto crypto;

    public SecretMigrator(ConfigEntryRepository repo, SecretCrypto crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (ConfigEntry e : repo.findAll()) {
            if (e.isSecret() && !crypto.isEncrypted(e.getPropValue()) && !e.getPropValue().isEmpty()) {
                e.setPropValue(crypto.encrypt(e.getPropValue()));
                repo.save(e);
            }
        }
    }
}
