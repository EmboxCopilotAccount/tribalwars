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

// "Opponents defeated" (the kills counter behind the ranking and profile) counts for the defender too.
@SpringBootTest
@Transactional
class KillStatsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired AchievementCounterRepository counters;
    @Autowired TickService tick;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private long kills(Account a, World w) {
        return counters.findByAccountIdAndWorldIdAndCounterKey(a.getId(), w.getId(), "kills").map(AchievementCounter::getValue).orElse(0L);
    }

    @Test
    void theDefenderGetsTheAttackersItKilled() {
        World world = worlds.createWorld("kill-stats-test", 1);
        Account attacker = account("attacker");
        Account defender = account("defender");
        Village home = worlds.joinWorld(world, attacker);
        Village target = worlds.joinWorld(world, defender);
        stocks.deleteAll(stocks.findByVillage(target));
        UnitStock spears = new UnitStock();
        spears.setVillage(target);
        spears.setType(UnitType.SPEAR);
        spears.setCount(2000);
        stocks.save(spears);

        Movement attack = new Movement();
        attack.setOriginVillage(home);
        attack.setTargetVillage(target);
        attack.setType(MovementType.ATTACK);
        attack.getUnits().putAll(Map.of(UnitType.AXE, 100));
        attack.setDepartedAt(Instant.now().minusSeconds(600));
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(attack);
        tick.tick();

        assertEquals(100, kills(defender, world)); // the whole attack died at the wall
    }
}
