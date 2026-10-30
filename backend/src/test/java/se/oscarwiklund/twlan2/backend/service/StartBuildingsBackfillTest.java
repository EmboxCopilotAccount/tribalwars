package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.BuildingRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import static org.junit.jupiter.api.Assertions.*;

// The startup backfill finds villages missing a building type with one query and only touches those.
@SpringBootTest
@Transactional
class StartBuildingsBackfillTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired VillageRepository villages;

    @Test
    void restoresAMissingBuildingAtItsStartLevel() {
        World world = worlds.createWorld("backfill-test", 1);
        Account a = new Account();
        a.setUsername("backfill-" + System.nanoTime());
        a.setPasswordHash("x");
        Village v = worlds.joinWorld(world, accounts.save(a));
        int types = BuildingType.values().length;
        assertFalse(villages.idsMissingBuildings(OwnerType.PLAYER, types).contains(v.getId()));

        buildings.delete(buildings.findByVillageAndType(v, BuildingType.WALL).orElseThrow());
        buildings.flush();
        assertTrue(villages.idsMissingBuildings(OwnerType.PLAYER, types).contains(v.getId()));

        worlds.run();
        assertEquals(BuildingType.WALL.startLevel(), buildings.findByVillageAndType(v, BuildingType.WALL).orElseThrow().getLevel());
        assertFalse(villages.idsMissingBuildings(OwnerType.PLAYER, types).contains(v.getId()));
    }

    // an account deleted while the server starts: its village is gone by the time the backfill gets to it
    @Test
    void skipsAVillageThatNoLongerExists() {
        assertDoesNotThrow(() -> worlds.backfillStartBuildings(java.util.List.of(Long.MAX_VALUE)));
    }
}
