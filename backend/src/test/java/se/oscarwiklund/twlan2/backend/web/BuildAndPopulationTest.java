package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.BuildingRepository;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;
import se.oscarwiklund.twlan2.backend.service.TrainService;
import se.oscarwiklund.twlan2.backend.service.VillageService;
import se.oscarwiklund.twlan2.backend.service.WorldService;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Population and build costs as the village screens see them must match what the server checks and charges.
@SpringBootTest
@Transactional
class BuildAndPopulationTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired UnitStockRepository stocks;
    @Autowired VillageService villageService;
    @Autowired TrainService train;
    @Autowired GameFacade facade;

    Village village;

    @BeforeEach
    void setUp() {
        World world = worlds.createWorld("build-pop-test", 1);
        Account player = new Account();
        player.setUsername("buildpoptester");
        player.setPasswordHash("x");
        accounts.save(player);
        AccountContext.set(player);
        WorldContext.set(world.getId());
        village = worlds.joinWorld(world, player);
    }

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    private void setLevel(BuildingType type, int level) {
        Building b = buildings.findByVillageAndType(village, type).orElseGet(() -> {
            Building n = new Building();
            n.setVillage(village);
            n.setType(type);
            return n;
        });
        b.setLevel(level);
        buildings.save(b);
    }

    @Test
    void dismissingTroopsDoesNotCountThemTwice() {
        setLevel(BuildingType.BARRACKS, 1);
        UnitStock spears = new UnitStock();
        spears.setVillage(village);
        spears.setType(UnitType.SPEAR);
        spears.setCount(50);
        stocks.save(spears);
        int before = villageService.populationUsed(village);

        train.decommission(village, UnitType.SPEAR, 20);

        assertEquals(before, villageService.populationUsed(village)); // the 20 still count once, until they leave
    }
}
