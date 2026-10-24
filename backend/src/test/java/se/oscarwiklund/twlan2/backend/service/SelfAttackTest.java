package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Attacking one of your own villages (world setting attackSelf) is a real battle; only troops whose target was
// conquered while they were on the road move in.
@SpringBootTest
@Transactional
class SelfAttackTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired CombatReportRepository reports;
    @Autowired MovementService movementService;
    @Autowired TickService tick;

    private void setStock(Village v, UnitType type, int count) {
        UnitStock s = stocks.findByVillageAndType(v, type).orElseGet(() -> {
            UnitStock n = new UnitStock();
            n.setVillage(v);
            n.setType(type);
            return n;
        });
        s.setCount(count);
        stocks.save(s);
    }

    private int stock(Village v, UnitType type) {
        return stocks.findByVillageAndType(v, type).map(UnitStock::getCount).orElse(0);
    }

    @Test
    void attackingYourOwnVillageFights() {
        World world = worlds.createWorld("self-attack-test", 1);
        Account a = new Account();
        a.setUsername("selfattacker");
        a.setPasswordHash("x");
        accounts.save(a);
        Village home = worlds.joinWorld(world, a);
        Village second = new Village();
        second.setName("Second");
        second.setWorld(world);
        second.setOwner(a);
        second.setOwnerType(OwnerType.PLAYER);
        second.setX(home.getX() < 500 ? home.getX() + 3 : home.getX() - 3);
        second.setY(home.getY());
        second.setResourcesSettledAt(Instant.now());
        villages.save(second);
        stocks.deleteAll(stocks.findByVillage(second));
        setStock(second, UnitType.SPEAR, 100);
        setStock(home, UnitType.AXE, 500);

        Movement attack = movementService.sendAttack(home, second, Map.of(UnitType.AXE, 500));
        assertTrue(attack.isSelfAttack());
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        tick.tick();

        assertTrue(stock(second, UnitType.SPEAR) < 100);     // the garrison fought
        assertEquals(0, stock(second, UnitType.AXE));        // and the axes did not move in
        assertTrue(reports.findByWorldIdOrderByOccurredAtDesc(world.getId()).size() > 0);
    }
}
