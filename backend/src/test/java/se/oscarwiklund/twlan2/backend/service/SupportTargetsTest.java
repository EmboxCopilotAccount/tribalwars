package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// World setting supportTargets: "players" (default) lets support go to any player's village, "tribe" only to the
// own villages and those of tribe members. Barbarian villages never take support.
@SpringBootTest
@Transactional
class SupportTargetsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired UnitStockRepository stocks;
    @Autowired VillageRepository villages;
    @Autowired MovementService movementService;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private Village withSpears(Village v) {
        UnitStock s = new UnitStock();
        s.setVillage(v);
        s.setType(UnitType.SPEAR);
        s.setCount(10);
        stocks.save(s);
        return v;
    }

    @Test
    void anyPlayerByDefaultTribeOnlyWhenSet() {
        World world = worlds.createWorld("support-targets-test", 1);
        Village from = withSpears(worlds.joinWorld(world, account("helper")));
        Village stranger = worlds.joinWorld(world, account("stranger"));

        assertNotNull(movementService.sendSupport(from, stranger, Map.of(UnitType.SPEAR, 2)));

        world.getSettings().put("supportTargets", "tribe");
        MovementService.MovementException refused = assertThrows(MovementService.MovementException.class,
                () -> movementService.sendSupport(from, stranger, Map.of(UnitType.SPEAR, 2)));
        assertTrue(refused.getMessage().contains("tribe members"));
    }

    @Test
    void barbarianVillagesTakeNoSupport() {
        World world = worlds.createWorld("support-barbarian-test", 1);
        Village from = withSpears(worlds.joinWorld(world, account("helper")));
        Village barbarian = worlds.joinWorld(world, account("leaver"));
        barbarian.setOwner(null);
        villages.save(barbarian);

        assertThrows(MovementService.MovementException.class, () -> movementService.sendSupport(from, barbarian, Map.of(UnitType.SPEAR, 2)));
    }
}
