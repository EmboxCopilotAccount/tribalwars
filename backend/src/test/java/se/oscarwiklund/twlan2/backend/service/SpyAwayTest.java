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

// A spy report's "troops outside" includes the village's support stationed in other villages.
@SpringBootTest
@Transactional
class SpyAwayTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired StationedTroopsRepository stationed;
    @Autowired CombatService combat;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    @Test
    void stationedSupportShowsAsTroopsOutside() {
        World world = worlds.createWorld("spy-away-test", 1);
        Village spy = worlds.joinWorld(world, account("spy"));
        Village target = worlds.joinWorld(world, account("watched"));
        Village host = worlds.joinWorld(world, account("host"));
        stocks.deleteAll(stocks.findByVillage(target));
        StationedTroops support = new StationedTroops();
        support.setOriginVillage(target);
        support.setHostVillage(host);
        support.getUnits().put(UnitType.SPEAR, 70);
        stationed.save(support);

        Movement scouts = new Movement();
        scouts.setOriginVillage(spy);
        scouts.setTargetVillage(target);
        scouts.setType(MovementType.ATTACK);
        scouts.getUnits().putAll(Map.of(UnitType.SCOUT, 10)); // enough for level 4: troops outside
        scouts.setDepartedAt(Instant.now().minusSeconds(600));
        scouts.setArrivesAt(Instant.now());
        movements.save(scouts);

        CombatReport report = combat.resolve(scouts).report();
        assertEquals(4, report.getDetail().get("spy_level"));
        assertEquals(70, report.getDetail().get("spy_away:SPEAR"));
    }
}
