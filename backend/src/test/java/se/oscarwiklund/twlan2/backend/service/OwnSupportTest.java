package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Support sent to another of your own villages stays the sending village's troops, as in the original: it can be
// withdrawn and doesn't join the other village's garrison.
@SpringBootTest
@Transactional
class OwnSupportTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired UnitStockRepository stocks;
    @Autowired SupportService support;
    @Autowired NobleService nobles;
    @Autowired TickService tick;

    Account me;
    Village home;
    Village second;

    @BeforeEach
    void setUp() {
        World world = worlds.createWorld("own-support-test", 1);
        me = account("player");
        home = worlds.joinWorld(world, me);
        second = worlds.joinWorld(world, account("former-owner"));
        second.setOwner(me); // a second village of the same player
        villages.save(second);
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private void supportArrives(Map<UnitType, Integer> units) {
        Movement m = new Movement();
        m.setOriginVillage(home);
        m.setTargetVillage(second);
        m.setType(MovementType.SUPPORT);
        m.getUnits().putAll(units);
        m.setDepartedAt(Instant.now().minusSeconds(600));
        m.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(m);
        tick.tick();
    }

    private int stockAt(Village v, UnitType type) {
        return stocks.findByVillageAndType(v, type).map(UnitStock::getCount).orElse(0);
    }

    @Test
    void supportToYourOwnVillageCanBeWithdrawn() {
        int before = stockAt(second, UnitType.SCOUT);
        supportArrives(Map.of(UnitType.SCOUT, 250));

        assertEquals(before, stockAt(second, UnitType.SCOUT)); // not part of that village's troops
        List<StationedTroops> away = support.stationedFrom(home);
        assertEquals(1, away.size());
        assertEquals(250, away.get(0).getUnits().get(UnitType.SCOUT));

        Movement back = support.withdraw(me, away.get(0).getId(), null);
        assertEquals(home.getId(), back.getTargetVillage().getId());
        assertEquals(250, back.getUnits().get(UnitType.SCOUT));
    }

    @Test
    void aNoblemanStationedInYourOtherVillageStillCounts() {
        supportArrives(Map.of(UnitType.SNOB, 1));
        assertEquals(1, nobles.info(me, home.getWorld()).existing());
    }

    @Test
    void npcsStillMoveTroopsForGood() {
        me.setNpc(true);
        accounts.save(me);
        int before = stockAt(second, UnitType.AXE);
        supportArrives(Map.of(UnitType.AXE, 100));
        assertEquals(before + 100, stockAt(second, UnitType.AXE)); // a dodge: NPCs never withdraw
    }
}
