package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// From headquarters level 15 a building can be taken down one level at a time: free, nothing refunded,
// never mixed with upgrades of the same building, and never below what a village needs.
@SpringBootTest
@Transactional
class DemolishTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired UnitStockRepository units;
    @Autowired BuildQueueItemRepository buildQueue;
    @Autowired BuildService buildService;
    @Autowired TickService tickService;
    @Autowired VillageService villageService;

    Village v;

    @BeforeEach
    void setUp() {
        World world = worlds.createWorld("demolish-test", 1);
        Account a = new Account();
        a.setUsername("demolisher-" + System.nanoTime());
        a.setPasswordHash("x");
        v = worlds.joinWorld(world, accounts.save(a));
        level(BuildingType.HEADQUARTERS, 15);
        level(BuildingType.FARM, 30);
        level(BuildingType.WAREHOUSE, 20);
        level(BuildingType.WALL, 3);
        v.setWood(50000);
        v.setClay(50000);
        v.setIron(50000);
    }

    @Test
    void needsHeadquarters15() {
        level(BuildingType.HEADQUARTERS, 14);
        assertThrows(BuildService.BuildException.class, () -> buildService.demolish(v, BuildingType.WALL));
    }

    @Test
    void takesOneLevelDownForFree() {
        double wood = v.getWood();
        BuildQueueItem item = buildService.demolish(v, BuildingType.WALL);
        assertTrue(item.isDemolish());
        assertEquals(2, item.getTargetLevel());
        assertNotNull(item.getCompletesAt()); // started at once on an empty queue
        assertEquals(wood, v.getWood(), 1.0); // nothing paid (only production since the start)

        tickService.completeBuildItem(item, item.getCompletesAt());
        assertEquals(2, villageService.levelOf(v, BuildingType.WALL));
        assertTrue(buildQueue.findByVillageOrderByPositionAsc(v).isEmpty());
    }

    @Test
    void upgradesAndDemolitionsDontMix() {
        buildService.demolish(v, BuildingType.WALL);
        assertThrows(BuildService.BuildException.class, () -> buildService.enqueue(v, BuildingType.WALL));
        buildService.enqueue(v, BuildingType.TIMBER_CAMP); // other buildings still queue
        buildService.enqueue(v, BuildingType.CLAY_PIT);
        assertThrows(BuildService.BuildException.class, () -> buildService.demolish(v, BuildingType.CLAY_PIT));
    }

    @Test
    void stopsAtTheMinimumLevel() {
        level(BuildingType.WAREHOUSE, 2);
        buildService.demolish(v, BuildingType.WAREHOUSE);
        assertThrows(BuildService.BuildException.class, () -> buildService.demolish(v, BuildingType.WAREHOUSE));
        level(BuildingType.WALL, 1);
        buildService.demolish(v, BuildingType.WALL); // a wall can go to 0
        assertThrows(BuildService.BuildException.class, () -> buildService.demolish(v, BuildingType.WALL));
    }

    @Test
    void theFarmMustStillFeedTheVillage() {
        level(BuildingType.FARM, 2);
        UnitStock spears = units.findByVillageAndType(v, UnitType.SPEAR).orElseGet(() -> {
            UnitStock n = new UnitStock();
            n.setVillage(v);
            n.setType(UnitType.SPEAR);
            return n;
        });
        spears.setCount(250); // more than a level 1 farm feeds (240)
        units.save(spears);
        assertThrows(BuildService.BuildException.class, () -> buildService.demolish(v, BuildingType.FARM));
        assertTrue(buildQueue.findByVillageOrderByPositionAsc(v).isEmpty());
        level(BuildingType.FARM, 30);
        buildService.demolish(v, BuildingType.FARM);
    }

    @Test
    void cancellingTakesTheLaterDemolitionsAlongWithoutRefund() {
        BuildQueueItem first = buildService.demolish(v, BuildingType.WALL);
        BuildQueueItem second = buildService.demolish(v, BuildingType.WALL);
        buildService.demolish(v, BuildingType.WALL);
        assertEquals(0, buildQueue.findByVillageOrderByPositionAsc(v).get(2).getTargetLevel());
        double wood = v.getWood();

        buildService.cancel(v, second.getId());

        List<BuildQueueItem> q = buildQueue.findByVillageOrderByPositionAsc(v);
        assertEquals(List.of(first.getId()), q.stream().map(BuildQueueItem::getId).toList());
        assertEquals(wood, v.getWood(), 1.0);
    }

    @Test
    void movedDemolitionsStillGoDownInOrder() {
        BuildQueueItem running = buildService.demolish(v, BuildingType.WALL);
        buildService.enqueue(v, BuildingType.TIMBER_CAMP);
        BuildQueueItem last = buildService.demolish(v, BuildingType.WALL);
        buildService.move(v, last.getId(), 1);

        List<BuildQueueItem> q = buildQueue.findByVillageOrderByPositionAsc(v);
        assertEquals(running.getId(), q.get(0).getId());
        assertEquals(2, q.get(0).getTargetLevel());
        assertEquals(last.getId(), q.get(1).getId());
        assertEquals(1, q.get(1).getTargetLevel());
        assertEquals(BuildingType.TIMBER_CAMP, q.get(2).getType());
    }

    private void level(BuildingType type, int level) {
        Building b = buildings.findByVillageAndType(v, type).orElseGet(() -> {
            Building n = new Building();
            n.setVillage(v);
            n.setType(type);
            return n;
        });
        b.setLevel(level);
        buildings.save(b);
    }
}
