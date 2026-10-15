package com.foundry.config;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundry.config.repo.ConfigEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:cfgtest;DB_CLOSE_DELAY=-1",
    "cfg.seed-defaults=false",
    "cfg.admin.username=boss",
    "cfg.admin.password=secret-admin",
    "cfg.token.secret=test-signing-secret-that-is-long-enough",
    "cfg.clients[0].id=svc",
    "cfg.clients[0].secret=svc-secret",
    "cfg.clients[0].apps[0]=backend",
    "cfg.clients[0].secrets=true",
    "cfg.clients[1].id=webui",
    "cfg.clients[1].secret=webui-secret",
    "cfg.clients[1].apps[0]=ui",
    "cfg.clients[1].secrets=false"
})
@AutoConfigureMockMvc
class ConfigServerTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConfigEntryRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"boss\",\"password\":\"secret-admin\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("boss"));
        return session;
    }

    /** What an application does first: trade its client id and secret for a token. */
    private String bearer(String id, String secret) throws Exception {
        String res = mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("clientId", id, "clientSecret", secret))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(res).get("accessToken").asText();
    }

    private String bearer() throws Exception {
        return bearer("svc", "svc-secret");
    }

    private void add(MockHttpSession s, String app, String profile, String key, String value, boolean secret) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("app", app, "profile", profile, "key", key, "value", value, "secret", secret));
        mvc.perform(post("/api/admin/entries").session(s).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }

    @Test
    void console_api_needs_the_admin_login() throws Exception {
        mvc.perform(get("/api/admin/entries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        MockHttpSession s = login();
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("boss"));
        mvc.perform(get("/api/admin/entries").session(s)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(s)).andExpect(status().isNoContent());
    }

    @Test
    void wrong_password_is_rejected_and_repeated_failures_lock_the_login() throws Exception {
        String bad = "{\"username\":\"lockme\",\"password\":\"nope\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void the_backend_account_reads_merged_settings_but_the_admin_session_and_wrong_clients_cannot() throws Exception {
        MockHttpSession s = login();
        add(s, "application", "default", "shared.key", "from-shared", false);
        add(s, "backend", "default", "gym.booking-horizon-days", "90", false);
        add(s, "backend", "prod", "gym.booking-horizon-days", "30", false);
        add(s, "backend", "default", "gym.admin.password", "hush", true);

        mvc.perform(get("/config/backend").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.properties['gym.booking-horizon-days']").value("90"))
            .andExpect(jsonPath("$.properties['shared.key']").value("from-shared"))
            .andExpect(jsonPath("$.properties['gym.admin.password']").value("hush"));

        mvc.perform(get("/config/backend?profiles=prod").header("Authorization", bearer()))
            .andExpect(jsonPath("$.properties['gym.booking-horizon-days']").value("30"))
            .andExpect(jsonPath("$.profiles[0]").value("prod"));

        mvc.perform(get("/config/backend")).andExpect(status().isUnauthorized());
        mvc.perform(get("/config/backend").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
        // The client id and secret are not accepted directly, only a token made from them
        mvc.perform(get("/config/backend").header("Authorization", "Basic c3ZjOnN2Yy1zZWNyZXQ=")).andExpect(status().isUnauthorized());
        // The admin's console session is not a key to /config
        mvc.perform(get("/config/backend").session(s)).andExpect(status().isUnauthorized());
        // and a token is not a key to the console
        mvc.perform(get("/api/admin/entries").header("Authorization", bearer())).andExpect(status().isUnauthorized());
    }

    @Test
    void an_application_setting_beats_a_shared_one_and_a_later_profile_beats_an_earlier_one() throws Exception {
        MockHttpSession s = login();
        add(s, "application", "default", "k", "shared-default", false);
        add(s, "application", "prod", "k", "shared-prod", false);
        add(s, "backend", "default", "k", "app-default", false);
        add(s, "backend", "a", "other", "A", false);
        add(s, "backend", "b", "other", "B", false);
        mvc.perform(get("/config/backend?profiles=prod").header("Authorization", bearer()))
            .andExpect(jsonPath("$.properties.k").value("app-default"));
        mvc.perform(get("/config/backend?profiles=a,b").header("Authorization", bearer()))
            .andExpect(jsonPath("$.properties.other").value("B"));
        mvc.perform(get("/config/backend?profiles=b,a").header("Authorization", bearer()))
            .andExpect(jsonPath("$.properties.other").value("A"));
    }

    @Test
    void the_ui_client_reads_only_its_own_non_secret_settings() throws Exception {
        MockHttpSession s = login();
        add(s, "application", "default", "shared.key", "from-shared", false);
        add(s, "ui", "default", "brand.name", "Gym One", false);
        add(s, "ui", "default", "leaky", "old-public-value", false);
        add(s, "ui", "prod", "leaky", "now-secret", true); // a winning secret hides the key completely
        add(s, "backend", "default", "gym.admin.password", "hush", true);
        String ui = bearer("webui", "webui-secret");

        mvc.perform(get("/config/ui").header("Authorization", ui))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties['brand.name']").value("Gym One"))
            .andExpect(jsonPath("$.properties", hasKey("leaky")))
            .andExpect(jsonPath("$.properties", not(hasKey("shared.key"))));
        mvc.perform(get("/config/ui?profiles=prod").header("Authorization", ui))
            .andExpect(jsonPath("$.properties", not(hasKey("leaky"))));
        // its token does not open the backend's settings, and the backend's token does not open the UI's
        mvc.perform(get("/config/backend").header("Authorization", ui)).andExpect(status().isForbidden());
        mvc.perform(get("/config/ui").header("Authorization", bearer())).andExpect(status().isForbidden());
    }

    @Test
    void tokens_need_the_right_client_secret_and_cannot_be_forged_or_stolen_from_the_old_open_endpoint() throws Exception {
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"svc\",\"clientSecret\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"nobody\",\"clientSecret\":\"svc-secret\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"svc\"}"))
            .andExpect(status().isBadRequest());

        // changing one letter of the payload or the signature makes the token worthless
        String token = bearer().substring("Bearer ".length());
        String[] parts = token.split("\\.");
        String forgedPayload = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1)
            + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];
        String forgedSignature = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1)
            + (parts[2].endsWith("A") ? "B" : "A");
        mvc.perform(get("/config/backend").header("Authorization", "Bearer " + forgedPayload)).andExpect(status().isUnauthorized());
        mvc.perform(get("/config/backend").header("Authorization", "Bearer " + forgedSignature)).andExpect(status().isUnauthorized());

        // the earlier open endpoint for browsers no longer exists
        mvc.perform(get("/public/config/ui")).andExpect(status().isNotFound());
    }

    @Test
    void repeated_wrong_client_secrets_lock_that_client_for_a_while() throws Exception {
        String bad = "{\"clientId\":\"lockclient\",\"clientSecret\":\"nope\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isTooManyRequests());
    }

    @Test
    void secrets_are_never_returned_to_the_console_and_an_empty_update_keeps_them() throws Exception {
        MockHttpSession s = login();
        add(s, "backend", "default", "token", "very-secret", true);
        String listing = mvc.perform(get("/api/admin/entries").session(s)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].secret").value(true)).andExpect(jsonPath("$[0].value").value(""))
            .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(listing.contains("very-secret"));
        mvc.perform(get("/api/admin/preview?app=backend").session(s))
            .andExpect(jsonPath("$[0].value").value("")).andExpect(jsonPath("$[0].secret").value(true));

        long id = repo.findAll().get(0).getId();
        // value omitted: keeps the stored secret
        mvc.perform(put("/api/admin/entries/" + id).session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"app\":\"backend\",\"profile\":\"default\",\"key\":\"token\",\"secret\":true,\"description\":\"renamed note\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/config/backend").header("Authorization", bearer()))
            .andExpect(jsonPath("$.properties.token").value("very-secret"));
        // un-marking a secret requires typing a value, so it cannot be exposed by accident
        mvc.perform(put("/api/admin/entries/" + id).session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"app\":\"backend\",\"profile\":\"default\",\"key\":\"token\",\"secret\":false}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void settings_can_be_edited_deleted_and_duplicates_are_refused() throws Exception {
        MockHttpSession s = login();
        add(s, "ui", "", "brand.name", "Foundry", false); // empty profile means default
        mvc.perform(post("/api/admin/entries").session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"app\":\"ui\",\"profile\":\"default\",\"key\":\"brand.name\",\"value\":\"Other\"}"))
            .andExpect(status().isConflict());
        long id = repo.findAll().get(0).getId();
        mvc.perform(put("/api/admin/entries/" + id).session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"app\":\"ui\",\"profile\":\"default\",\"key\":\"brand.name\",\"value\":\"Gym One\",\"secret\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("Gym One")).andExpect(jsonPath("$.updatedBy").value("boss"));
        mvc.perform(get("/api/admin/apps").session(s)).andExpect(jsonPath("$.apps", hasItem("ui")));
        mvc.perform(delete("/api/admin/entries/" + id).session(s)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/entries/" + id).session(s)).andExpect(status().isNotFound());
    }

    @Test
    void invalid_names_are_refused() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/admin/entries").session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"app\":\"bad name!\",\"key\":\"k\",\"value\":\"v\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/config/back..end").header("Authorization", bearer())).andExpect(status().isForbidden()); // not an application this token lists
    }
}
