package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Returning loot fills the warehouse up, but never lowers a stock that is already above it (e.g. after catapults hit
// the warehouse).
@SpringBootTest
@Transactional
class LootAboveCapacityTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired VillageService villageService;
    @Autowired TickService tick;

    @Test
    void lootDoesNotLowerAnOverfullStock() {
        World world = worlds.createWorld("loot-test", 1);
        Account a = new Account();
        a.setUsername("looter-" + System.nanoTime());
        a.setPasswordHash("x");
        Village home = worlds.joinWorld(world, accounts.save(a));
        int capacity = villageService.warehouseCapacity(home);
        home.setWood(capacity + 500);
        home.setClay(capacity - 100);
        home.setIron(0);
        villages.save(home);

        Movement back = new Movement();
        back.setOriginVillage(home);
        back.setTargetVillage(home);
        back.setType(MovementType.RETURN);
        back.getUnits().putAll(Map.of(UnitType.LIGHT, 10));
        back.setCarriedWood(200);
        back.setCarriedClay(300);
        back.setCarriedIron(50);
        back.setDepartedAt(Instant.now().minusSeconds(60));
        back.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(back);

        tick.tick();

        Village v = villages.findById(home.getId()).orElseThrow();
        assertEquals(capacity + 500, v.getWood(), 1.0); // stays above (production may add a fraction)
        assertEquals(capacity, v.getClay(), 0.001);
        assertEquals(50, v.getIron(), 1.0);
    }
}
