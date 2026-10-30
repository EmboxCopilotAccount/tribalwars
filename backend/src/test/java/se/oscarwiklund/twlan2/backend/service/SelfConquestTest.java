package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// #125: a nobleman taking one of your own villages' loyalty to 0 (world setting attackSelf) is a conquest (achievement
// "Self-conquest"): the nobleman is used up and loyalty starts over, but nothing changes hands, so the village keeps its
// orders and the armies it has out (the handover used to wipe them as if they were a previous owner's).
@SpringBootTest
@Transactional
class SelfConquestTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired VillageService villageService;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired BuildQueueItemRepository buildQueue;
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

    @Test
    void usesUpTheNoblemanButKeepsTheVillagesOrdersAndArmies() {
        World world = worlds.createWorld("self-conquest-test", 1);
        Account a = new Account();
        a.setUsername("selfconqueror-" + System.nanoTime());
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
        villageService.setLoyalty(second, 10);
        setStock(home, UnitType.AXE, 500);
        setStock(home, UnitType.SNOB, 1);
        // an army of the second village, out on the road to the home village (support)
        setStock(second, UnitType.SWORD, 50);
        Movement away = movementService.sendSupport(second, home, Map.of(UnitType.SWORD, 50));
        BuildQueueItem q = new BuildQueueItem();
        q.setVillage(second);
        q.setType(BuildingType.WALL);
        q.setTargetLevel(1);
        q.setStartedAt(Instant.now());
        q.setCompletesAt(Instant.now().plusSeconds(3600));
        q.setPosition(0);
        buildQueue.save(q);

        Movement attack = movementService.sendAttack(home, second, Map.of(UnitType.AXE, 500, UnitType.SNOB, 1));
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        tick.tick();

        CombatReport r = reports.findByWorldIdOrderByOccurredAtDesc(world.getId()).get(0);
        List<Movement> back = movements.findAll().stream()
                .filter(m -> m.getType() == MovementType.RETURN && m.getTargetVillage().getId().equals(home.getId())).toList();
        assertTrue(r.isConquered());
        assertEquals(a.getId(), villages.findById(second.getId()).orElseThrow().getOwner().getId());
        assertEquals(25, (int) villageService.loyaltyOf(second));
        assertEquals(0, back.stream().mapToInt(m -> m.getUnits().getOrDefault(UnitType.SNOB, 0)).sum()); // used up
        assertEquals(500, back.stream().mapToInt(m -> m.getUnits().getOrDefault(UnitType.AXE, 0)).sum());
        assertTrue(movements.findById(away.getId()).isPresent());                // its swordsmen are still on the road
        assertEquals(1, buildQueue.findByVillageOrderByPositionAsc(second).size()); // and its wall still being built
    }
}
