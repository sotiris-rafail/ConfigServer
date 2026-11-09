package com.foundry.config.web;

import java.util.List;

import com.foundry.config.dto.Dtos.AppsView;
import com.foundry.config.dto.Dtos.EntryRequest;
import com.foundry.config.dto.Dtos.EntryView;
import com.foundry.config.dto.Dtos.PreviewRow;
import com.foundry.config.dto.Dtos.ClientView;
import com.foundry.config.dto.Dtos.CloneRequest;
import com.foundry.config.dto.Dtos.CodeRequest;
import com.foundry.config.dto.Dtos.HistoryView;
import com.foundry.config.dto.Dtos.ImportRequest;
import com.foundry.config.service.ClientService;
import com.foundry.config.service.ConfigService;
import com.foundry.config.service.HistoryService;
import com.foundry.config.service.RefreshService;
import com.foundry.config.service.TotpService;
import com.foundry.config.service.TransferService;
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
    private final HistoryService history;
    private final ClientService clients;
    private final TransferService transfer;
    private final RefreshService refresh;
    private final TotpService totp;

    public AdminController(ConfigService service, HistoryService history, ClientService clients, TransferService transfer,
                           RefreshService refresh, TotpService totp) {
        this.service = service;
        this.history = history;
        this.clients = clients;
        this.transfer = transfer;
        this.refresh = refresh;
        this.totp = totp;
    }

    /** Settings that are switched on but cannot work (a flag whose prerequisite is off, a missing key). */
    @GetMapping("/warnings")
    public List<String> warnings(@RequestParam(defaultValue = "backend") String app, @RequestParam(required = false) String profiles) {
        return service.warnings(app, profiles);
    }

    @GetMapping("/history")
    public List<HistoryView> history(@RequestParam(required = false) String app, @RequestParam(required = false) String key,
                                     @RequestParam(defaultValue = "100") int limit) {
        return history.latest(app, key, limit);
    }

    @PostMapping("/history/{id}/undo")
    public EntryView undo(@PathVariable Long id, Authentication auth) {
        return service.undo(id, auth.getName());
    }

    @GetMapping("/clients")
    public List<ClientView> clients() {
        return clients.list();
    }

    @PutMapping("/clients/{id}/disabled")
    public List<ClientView> disable(@PathVariable String id, @RequestParam boolean value) {
        clients.setDisabled(id, value);
        return clients.list();
    }

    /** Asks the applications that have a refresh address to read their settings again now. */
    @PostMapping("/refresh")
    public List<java.util.Map<String, Object>> refreshNow() {
        return refresh.refreshAll();
    }

    /** All settings as a file. Secrets are left out unless {@code secrets=true}. */
    @GetMapping("/export")
    public java.util.Map<String, Object> export(@RequestParam(required = false) String app, @RequestParam(required = false) String profile,
                                                @RequestParam(defaultValue = "false") boolean secrets) {
        return java.util.Map.of("version", 1, "entries", service.exportAll(app, profile, secrets));
    }

    @PostMapping("/import")
    public java.util.Map<String, Object> importFile(@RequestBody ImportRequest body, Authentication auth) {
        return transfer.importEntries(body.entries(), body.overwrite(), body.dryRun(), auth.getName());
    }

    /** Copies every setting of one profile into another (for example prod into staging). */
    @PostMapping("/clone")
    public java.util.Map<String, Object> clone(@RequestBody CloneRequest body, Authentication auth) {
        return transfer.clone(body.app(), body.from(), body.to(), body.overwrite(), auth.getName());
    }

    @GetMapping("/2fa")
    public java.util.Map<String, Object> twoFactor(Authentication auth) {
        return java.util.Map.of("enabled", totp.enabled(auth.getName()));
    }

    @PostMapping("/2fa/setup")
    public java.util.Map<String, String> twoFactorSetup(Authentication auth) {
        String[] s = totp.setup(auth.getName());
        return java.util.Map.of("secret", s[0], "uri", s[1]);
    }

    @PostMapping("/2fa/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void twoFactorEnable(@RequestBody CodeRequest body, Authentication auth) {
        totp.enable(auth.getName(), body.code());
    }

    @PostMapping("/2fa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void twoFactorDisable(@RequestBody CodeRequest body, Authentication auth) {
        totp.disable(auth.getName(), body.code());
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
    public void delete(@PathVariable Long id, Authentication auth) {
        service.delete(id, auth.getName());
    }

    @GetMapping("/preview")
    public List<PreviewRow> preview(@RequestParam String app, @RequestParam(required = false) String profiles) {
        return service.preview(app, profiles);
    }
}
