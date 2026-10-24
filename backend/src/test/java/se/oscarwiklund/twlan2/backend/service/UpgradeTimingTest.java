package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// An upgrade only counts from the moment it is finished: the hours before it are paid at the old level and cap.
@SpringBootTest
@Transactional
class UpgradeTimingTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired BuildQueueItemRepository buildQueue;
    @Autowired VillageService villageService;
    @Autowired TickService tick;

    @Test
    void aWarehouseUpgradeDoesNotStoreWhatOverflowedBeforeIt() {
        World world = worlds.createWorld("upgrade-timing-test", 1);
        Account a = new Account();
        a.setUsername("upgradetimer");
        a.setPasswordHash("x");
        Village v = worlds.joinWorld(world, accounts.save(a));
        Building warehouse = buildings.findByVillageAndType(v, BuildingType.WAREHOUSE).orElseThrow();
        warehouse.setLevel(1);
        buildings.save(warehouse);
        int oldCap = villageService.warehouseCapacity(v);
        v.setWood(oldCap);
        v.setResourcesSettledAt(Instant.now().minusSeconds(10 * 3600)); // full for the last 10 hours

        BuildQueueItem order = new BuildQueueItem();
        order.setVillage(v);
        order.setType(BuildingType.WAREHOUSE);
        order.setTargetLevel(2);
        order.setPosition(0);
        order.setStartedAt(Instant.now().minusSeconds(3600));
        order.setCompletesAt(Instant.now().minusSeconds(60)); // done a minute ago
        buildQueue.save(order);
        tick.tick();
        villageService.settleResources(v); // what the next look at the village does

        assertEquals(2, villageService.levelOf(v, BuildingType.WAREHOUSE));
        assertTrue(villageService.warehouseCapacity(v) > oldCap);
        assertTrue(v.getWood() < oldCap + 50, "only the last minute fills the bigger warehouse: " + v.getWood());
    }
}
