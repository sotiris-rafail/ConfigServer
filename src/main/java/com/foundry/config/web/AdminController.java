package com.foundry.config.web;

import java.util.List;

import com.foundry.config.dto.Dtos.AppsView;
import com.foundry.config.dto.Dtos.EntryRequest;
import com.foundry.config.dto.Dtos.EntryView;
import com.foundry.config.dto.Dtos.PreviewRow;
import com.foundry.config.service.ConfigService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The console's API. Secret values are never sent back: they show as an empty value with secret = true. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ConfigService service;

    public AdminController(ConfigService service) {
        this.service = service;
    }

    @GetMapping("/apps")
    public AppsView apps() {
        return service.apps();
    }

    @GetMapping("/entries")
    public List<EntryView> list(@RequestParam(required = false) String app, @RequestParam(required = false) String profile) {
        return service.list(app, profile);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView create(@Valid @RequestBody EntryRequest body, Authentication auth) {
        return service.create(body, auth.getName());
    }

    @PutMapping("/entries/{id}")
    public EntryView update(@PathVariable Long id, @Valid @RequestBody EntryRequest body, Authentication auth) {
        return service.update(id, body, auth.getName());
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/preview")
    public List<PreviewRow> preview(@RequestParam String app, @RequestParam(required = false) String profiles) {
        return service.preview(app, profiles);
    }
}
