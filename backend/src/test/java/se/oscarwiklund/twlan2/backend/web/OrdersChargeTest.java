package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.web.dto.BuildRequest;

import static org.junit.jupiter.api.Assertions.*;

// An order through the real controller must leave its cost in the database, not just in the response. Not
// @Transactional on purpose: a test transaction keeps the village managed and hides a missing save (2026-10-26: after
// the #89 change nothing cost resources on the live game while every @Transactional test passed).
@SpringBootTest
class OrdersChargeTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired BuildController builds;

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    @Test
    void buildOrderCostIsSaved() {
        World world = worlds.createWorld("orders-charge-test", 1);
        Account a = new Account();
        a.setUsername("payer-" + System.nanoTime());
        a.setPasswordHash("x");
        a = accounts.save(a);
        Village v = worlds.joinWorld(world, a);
        double woodBefore = villages.findById(v.getId()).orElseThrow().getWood();

        WorldContext.set(world.getId());
        AccountContext.set(a);
        var dto = builds.build(new BuildRequest("TIMBER_CAMP"));

        double woodAfter = villages.findById(v.getId()).orElseThrow().getWood();
        assertTrue(woodAfter < woodBefore - 1, "wood in the database " + woodBefore + " -> " + woodAfter);
        assertEquals(woodAfter, dto.wood(), 1.0); // what the player sees is what is stored
    }
}
