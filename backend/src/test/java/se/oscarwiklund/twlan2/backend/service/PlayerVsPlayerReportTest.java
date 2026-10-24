package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// A real player attacking another real player's empty village: nobody loses a unit, and copying those empty
// losses for the defender's report used to throw, which failed the whole tick on every retry.
@SpringBootTest
@Transactional
class PlayerVsPlayerReportTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired MovementRepository movements;
    @Autowired UnitStockRepository stocks;
    @Autowired TickService tick;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    @Test
    void attackOnAnEmptyPlayerVillageResolves() {
        World world = worlds.createWorld("pvp-report-test", 1);
        Village home = worlds.joinWorld(world, account("attacker"));
        Village target = worlds.joinWorld(world, account("defender"));
        stocks.deleteAll(stocks.findByVillage(target));

        Movement attack = new Movement();
        attack.setOriginVillage(home);
        attack.setTargetVillage(target);
        attack.setType(MovementType.ATTACK);
        attack.getUnits().putAll(Map.of(UnitType.AXE, 100));
        attack.setDepartedAt(Instant.now().minusSeconds(600));
        attack.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(attack);

        tick.tick();

        long returning = movements.findByOriginVillageOrTargetVillage(home, home).stream()
                .filter(m -> m.getType() == MovementType.RETURN).count();
        assertEquals(1, returning); // resolved, and the axes are on their way home
    }
}
