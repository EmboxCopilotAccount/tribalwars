package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.PlayerWarRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.AdminService;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.WorldService;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #118 through the HTTP API, committed (no test transaction), checked against what is stored afterwards.
@SpringBootTest
@AutoConfigureMockMvc
class WarEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired PlayerWarRepository rows;
    @Autowired AdminService admin;

    World world;
    Account human, npc, other;
    String token;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("war-endpoints-test", 1);
        AuthService.Login login = auth.register("warHuman" + System.nanoTime() % 100000, "secret1");
        human = login.account();
        token = login.token();
        other = auth.register("warOther" + System.nanoTime() % 100000, "secret1").account();
        Account n = new Account();
        n.setUsername("warNpc" + System.nanoTime() % 100000);
        n.setPasswordHash("x");
        n.setNpc(true);
        npc = accounts.save(n);
        village(human, 100, 100);
        village(npc, 103, 100);
        village(other, 106, 100);
    }

    @AfterEach
    void cleanUp() {
        admin.deleteWorld(world.getId()); // takes the NPC (it has no other world) and the wars with it
        Account actor = new Account();
        admin.deleteAccount(human.getId(), actor);
        admin.deleteAccount(other.getId(), actor);
    }

    private void village(Account owner, int x, int y) {
        Village v = new Village();
        v.setName(owner.getUsername());
        v.setWorld(world);
        v.setOwner(owner);
        v.setOwnerType(OwnerType.PLAYER);
        v.setX(x);
        v.setY(y);
        v.setResourcesSettledAt(Instant.now());
        villages.save(v);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r) {
        return r.header("Authorization", "Bearer " + token).header("X-World-Id", world.getId().toString());
    }

    @Test
    void declareThenPeaceAfterTheCooldown() throws Exception {
        mvc.perform(as(get("/api/players/" + npc.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDeclareWar").value(true))
                .andExpect(jsonPath("$.war").isEmpty())
                .andExpect(jsonPath("$.warPeaceHours").value(24.0));

        mvc.perform(as(post("/api/players/" + npc.getUsername() + "/war")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDeclareWar").value(false))
                .andExpect(jsonPath("$.war.declaredBy").value("PLAYER"))
                .andExpect(jsonPath("$.war.viaTribe").value(false));
        PlayerWar w = rows.findByWorldIdAndPlayerAccountIdAndNpcAccountId(world.getId(), human.getId(), npc.getId()).orElseThrow();
        assertFalse(w.isPending());

        mvc.perform(as(get("/api/players/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wars[0].opponent").value(npc.getUsername()));

        mvc.perform(as(post("/api/players/" + npc.getUsername() + "/war"))).andExpect(status().isBadRequest());
        mvc.perform(as(delete("/api/players/" + npc.getUsername() + "/war")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.peaceFrom").value(w.getStartedAt().plus(Duration.ofHours(24)).toString()));
        assertTrue(rows.findById(w.getId()).isPresent());

        w.setStartedAt(Instant.now().minus(Duration.ofHours(25)));
        rows.save(w);
        mvc.perform(as(delete("/api/players/" + npc.getUsername() + "/war")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.war").isEmpty())
                .andExpect(jsonPath("$.canDeclareWar").value(true));
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }

    @Test
    void onlyNpcsAndOnlyLoggedIn() throws Exception {
        mvc.perform(as(post("/api/players/" + other.getUsername() + "/war"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/players/" + npc.getUsername() + "/war").header("X-World-Id", world.getId().toString()))
                .andExpect(status().isUnauthorized());
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }
}
