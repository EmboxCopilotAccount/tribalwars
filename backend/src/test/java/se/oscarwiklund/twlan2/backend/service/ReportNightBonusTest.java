package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// A battle report keeps the night bonus the defence fought with (100 by day) and the morale, so both can be shown;
// spying has neither.
@SpringBootTest
@Transactional
class ReportNightBonusTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired MovementRepository movements;
    @Autowired CombatService combat;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    // night hours from `fromNow` to `toNow` hours away from the current time (wrapping past midnight is fine)
    private static void night(World world, int fromNow, int toNow) {
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
        world.getSettings().put("nightStart", LocalTime.now().plusHours(fromNow).format(hhmm));
        world.getSettings().put("nightEnd", LocalTime.now().plusHours(toNow).format(hhmm));
        world.getSettings().put("nightBonus", "2");
    }

    private CombatReport attack(World world, Map<UnitType, Integer> units) {
        Village attacker = worlds.joinWorld(world, account("attacker"));
        Village defender = worlds.joinWorld(world, account("defender"));
        Movement m = new Movement();
        m.setOriginVillage(attacker);
        m.setTargetVillage(defender);
        m.setType(MovementType.ATTACK);
        m.getUnits().putAll(units);
        m.setDepartedAt(Instant.now().minusSeconds(600));
        m.setArrivesAt(Instant.now());
        movements.save(m);
        return combat.resolve(m).report();
    }

    @Test
    void aBattleAtNightRecordsTheBonus() {
        World world = worlds.createWorld("night-report-test", 1);
        night(world, -1, 1);
        CombatReport report = attack(world, Map.of(UnitType.AXE, 50));
        assertEquals(200, report.getDetail().get("night"));
        assertEquals(100, report.getDetail().get("morale"));
    }

    @Test
    void aBattleByDayRecordsNoBonus() {
        World world = worlds.createWorld("day-report-test", 1);
        night(world, 2, 3);
        CombatReport report = attack(world, Map.of(UnitType.AXE, 50));
        assertEquals(100, report.getDetail().get("night"));
    }

    @Test
    void spyingRecordsNeitherNightNorMorale() {
        World world = worlds.createWorld("night-spy-test", 1);
        night(world, -1, 1);
        CombatReport report = attack(world, Map.of(UnitType.SCOUT, 5));
        assertNull(report.getDetail().get("night"));
        assertNull(report.getDetail().get("morale"));
    }
}
