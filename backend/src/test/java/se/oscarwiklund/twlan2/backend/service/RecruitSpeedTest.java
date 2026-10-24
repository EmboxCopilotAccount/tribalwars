package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.BuildingRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Recruit time follows the original's curve: base * 2/3 / 1.06^level of the recruit building.
@SpringBootTest
@Transactional
class RecruitSpeedTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired VillageService villageService;
    @Autowired TrainService train;

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

    @Test
    void barracksLevelSpeedsUpRecruitingLikeTheOriginal() {
        World world = worlds.createWorld("recruit-speed-test", 1);
        Account a = new Account();
        a.setUsername("recruitspeedtester");
        a.setPasswordHash("x");
        Village v = worlds.joinWorld(world, accounts.save(a));
        setLevel(v, BuildingType.WAREHOUSE, 20);
        setLevel(v, BuildingType.FARM, 20);
        v.setWood(10_000);
        v.setClay(10_000);
        v.setIron(10_000);

        setLevel(v, BuildingType.BARRACKS, 1);
        assertEquals(1020 * 2.0 / 3 / 1.06, train.enqueue(v, UnitType.SPEAR, 1).getPerUnitSeconds(), 1.0); // ~641 s
        setLevel(v, BuildingType.BARRACKS, 25);
        assertEquals(1020 * 2.0 / 3 / Math.pow(1.06, 25), train.enqueue(v, UnitType.SPEAR, 1).getPerUnitSeconds(), 1.0); // ~159 s
    }
}
