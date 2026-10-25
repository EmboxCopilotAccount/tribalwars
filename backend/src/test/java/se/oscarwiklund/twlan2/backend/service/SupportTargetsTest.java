package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// World setting supportTargets: "any" (default) lets support go to any village, barbarian ones included, and stay there;
// "tribe" only to the own villages and those of tribe members. The rule holds when the troops are sent and when they arrive.
@SpringBootTest
@Transactional
class SupportTargetsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired UnitStockRepository stocks;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired StationedTroopsRepository stationed;
    @Autowired MovementService movementService;
    @Autowired SupportService supportService;
    @Autowired TickService tick;

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

    private Movement arrive(Movement m) {
        m.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(m);
        tick.tick();
        return m;
    }

    @Test
    void strangersAndBarbariansTakeSupportByDefault() {
        World world = worlds.createWorld("support-any-test", 1);
        Account helper = account("helper");
        Village from = withSpears(worlds.joinWorld(world, helper));
        Village stranger = worlds.joinWorld(world, account("stranger"));
        Village barbarian = worlds.joinWorld(world, account("leaver"));
        barbarian.setOwner(null);
        villages.save(barbarian);

        arrive(movementService.sendSupport(from, stranger, Map.of(UnitType.SPEAR, 2)));
        arrive(movementService.sendSupport(from, barbarian, Map.of(UnitType.SPEAR, 3)));

        StationedTroops atStranger = stationed.findByHostVillageAndOriginVillage(stranger, from).orElseThrow();
        StationedTroops atBarbarian = stationed.findByHostVillageAndOriginVillage(barbarian, from).orElseThrow();
        assertEquals(2, atStranger.getUnits().get(UnitType.SPEAR));
        assertEquals(3, atBarbarian.getUnits().get(UnitType.SPEAR));

        supportService.withdraw(helper, atBarbarian.getId(), null); // the owner can still call them home
        assertTrue(stationed.findByHostVillageAndOriginVillage(barbarian, from).isEmpty());
    }

    @Test
    void tribeOnlyWorldsRefuseAndTurnRound() {
        World world = worlds.createWorld("support-tribe-test", 1);
        Village from = withSpears(worlds.joinWorld(world, account("helper")));
        Village stranger = worlds.joinWorld(world, account("stranger"));
        Movement onTheRoad = movementService.sendSupport(from, stranger, Map.of(UnitType.SPEAR, 2));

        world.getSettings().put("supportTargets", "tribe");
        MovementService.MovementException refused = assertThrows(MovementService.MovementException.class,
                () -> movementService.sendSupport(from, stranger, Map.of(UnitType.SPEAR, 2)));
        assertTrue(refused.getMessage().contains("tribe members"));

        arrive(onTheRoad); // sent before the switch: arrives on a tribe-only world and turns round
        assertTrue(stationed.findByHostVillageAndOriginVillage(stranger, from).isEmpty());
    }
}
