package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Catapults aimed at a building hit that building only; aimed at one that isn't built, they hit nothing.
@SpringBootTest
@Transactional
class CatapultAimTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
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

    private void setLevel(Village v, BuildingType type, int level) {
        Building b = buildings.findByVillageAndType(v, type).orElseGet(() -> {
            Building n = new Building();
            n.setVillage(v);
            n.setType(type);
            return n;
        });
        b.setLevel(level);
        buildings.save(b);
    }

    private Map<BuildingType, Integer> levels(Village v) {
        Map<BuildingType, Integer> m = new EnumMap<>(BuildingType.class);
        for (Building b : buildings.findByVillage(v)) m.put(b.getType(), b.getLevel());
        return m;
    }

    private void attack(Village from, Village to, BuildingType aim) {
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(MovementType.ATTACK);
        m.getUnits().putAll(Map.of(UnitType.AXE, 3000, UnitType.CATAPULT, 100));
        m.setCatapultTarget(aim);
        m.setDepartedAt(Instant.now().minusSeconds(600));
        m.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(m);
        tick.tick();
    }

    @Test
    void aimedCatapultsHitOnlyTheirTarget() {
        World world = worlds.createWorld("catapult-aim-test", 1);
        Village home = worlds.joinWorld(world, account("catapulter"));
        Village target = worlds.joinWorld(world, account("aimed-at"));
        stocks.deleteAll(stocks.findByVillage(target));
        setLevel(target, BuildingType.SMITHY, 10);
        setLevel(target, BuildingType.BARRACKS, 10);
        setLevel(target, BuildingType.STABLE, 0);

        Map<BuildingType, Integer> before = levels(target);
        attack(home, target, BuildingType.SMITHY);
        Map<BuildingType, Integer> after = levels(target);
        assertTrue(after.get(BuildingType.SMITHY) < 10);
        after.remove(BuildingType.SMITHY);
        before.remove(BuildingType.SMITHY);
        assertEquals(before, after); // nothing else was touched

        Map<BuildingType, Integer> beforeMiss = levels(target);
        attack(home, target, BuildingType.STABLE); // not built: the shot misses
        assertEquals(beforeMiss, levels(target));
    }
}
