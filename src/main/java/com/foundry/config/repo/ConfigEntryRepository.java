package com.foundry.config.repo;

import java.util.List;
import java.util.Optional;

import com.foundry.config.domain.ConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigEntryRepository extends JpaRepository<ConfigEntry, Long> {

    List<ConfigEntry> findAllByOrderByAppNameAscProfileNameAscPropKeyAsc();

    List<ConfigEntry> findByAppNameAndProfileNameOrderByPropKeyAsc(String appName, String profileName);

    Optional<ConfigEntry> findByAppNameAndProfileNameAndPropKey(String appName, String profileName, String propKey);
}
