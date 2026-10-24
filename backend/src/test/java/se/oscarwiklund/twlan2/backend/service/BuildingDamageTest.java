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

// A queued upgrade of a building that rams or catapults damage builds on from the damaged level:
// it must not restore the level it was ordered at.
@SpringBootTest
@Transactional
class BuildingDamageTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired BuildQueueItemRepository buildQueue;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired VillageService villageService;
    @Autowired TickService tick;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    @Test
    void queuedWallUpgradeBuildsOnFromTheRammedLevel() {
        World world = worlds.createWorld("damage-test", 1);
        Village home = worlds.joinWorld(world, account("rammer"));
        Village target = worlds.joinWorld(world, account("walled"));
        stocks.deleteAll(stocks.findByVillage(target));
        Building wall = buildings.findByVillageAndType(target, BuildingType.WALL).orElseGet(() -> {
            Building b = new Building();
            b.setVillage(target);
            b.setType(BuildingType.WALL);
            return b;
        });
        wall.setLevel(10);
        buildings.save(wall);

        BuildQueueItem order = new BuildQueueItem();
        order.setVillage(target);
        order.setType(BuildingType.WALL);
        order.setTargetLevel(11);
        order.setPosition(0);
        order.setStartedAt(Instant.now());
        order.setCompletesAt(Instant.now().plusSeconds(3600));
        buildQueue.save(order);

        Movement attack = new Movement();
        attack.setOriginVillage(home);
        attack.setTargetVillage(target);
        attack.setType(MovementType.ATTACK);
        attack.getUnits().putAll(Map.of(UnitType.AXE, 3000, UnitType.RAM, 200));
        attack.setDepartedAt(Instant.now().minusSeconds(600));
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(attack);
        tick.tick();

        int rammed = villageService.levelOf(target, BuildingType.WALL);
        assertTrue(rammed < 10);
        assertEquals(rammed + 1, buildQueue.findById(order.getId()).orElseThrow().getTargetLevel());

        order.setCompletesAt(Instant.now().minusSeconds(1));
        buildQueue.save(order);
        tick.tick();
        assertEquals(rammed + 1, villageService.levelOf(target, BuildingType.WALL));
    }

    @Test
    void ramsDamageTheWallEvenWhenTheAttackFails() {
        World world = worlds.createWorld("ram-loss-test", 1);
        Village home = worlds.joinWorld(world, account("rammer"));
        Village target = worlds.joinWorld(world, account("walled"));
        stocks.deleteAll(stocks.findByVillage(target));
        UnitStock spears = new UnitStock();
        spears.setVillage(target);
        spears.setType(UnitType.SPEAR);
        spears.setCount(5000);
        stocks.save(spears);
        Building wall = buildings.findByVillageAndType(target, BuildingType.WALL).orElseGet(() -> {
            Building b = new Building();
            b.setVillage(target);
            b.setType(BuildingType.WALL);
            return b;
        });
        wall.setLevel(10);
        buildings.save(wall);

        Movement attack = new Movement();
        attack.setOriginVillage(home);
        attack.setTargetVillage(target);
        attack.setType(MovementType.ATTACK);
        attack.getUnits().putAll(Map.of(UnitType.RAM, 100));
        attack.setDepartedAt(Instant.now().minusSeconds(600));
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(attack);
        tick.tick();

        assertTrue(movements.findByOriginVillageOrTargetVillage(home, home).isEmpty()); // the rams died...
        assertEquals(BattleCalculator.wallInBattle(100, 10), villageService.levelOf(target, BuildingType.WALL)); // ...but hit the wall
        assertTrue(villageService.levelOf(target, BuildingType.WALL) < 10);
    }
}
