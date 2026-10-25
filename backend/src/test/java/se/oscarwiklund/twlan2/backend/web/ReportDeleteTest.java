package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.CombatService;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.web.dto.ReportDto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Players delete their own reports on the server; another player's copy of the same battle stays.
@SpringBootTest
@Transactional
class ReportDeleteTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired MovementRepository movements;
    @Autowired CombatService combat;
    @Autowired GameFacade facade;

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private List<Long> reportIdsOf(Account a) {
        AccountContext.set(a);
        return facade.listReports().stream().map(ReportDto::id).toList();
    }

    @Test
    void eachPlayerDeletesOnlyTheirOwnReports() {
        World world = worlds.createWorld("report-delete-test", 1);
        WorldContext.set(world.getId());
        Account attacker = account("attacker");
        Account defender = account("defender");
        Village from = worlds.joinWorld(world, attacker);
        Village to = worlds.joinWorld(world, defender);
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(MovementType.ATTACK);
        m.getUnits().putAll(Map.of(UnitType.AXE, 20));
        m.setDepartedAt(Instant.now().minusSeconds(600));
        m.setArrivesAt(Instant.now());
        movements.save(m);
        combat.resolve(m);

        List<Long> attackerReports = reportIdsOf(attacker);
        List<Long> defenderReports = reportIdsOf(defender);
        assertEquals(1, attackerReports.size());
        assertEquals(1, defenderReports.size());

        // the attacker asks to delete both reports: only their own goes
        AccountContext.set(attacker);
        assertEquals(1, facade.deleteReports(List.of(attackerReports.get(0), defenderReports.get(0))));
        assertTrue(reportIdsOf(attacker).isEmpty());
        assertEquals(defenderReports, reportIdsOf(defender));

        AccountContext.set(defender);
        assertEquals(0, facade.deleteReports(List.of()));
        assertEquals(1, facade.deleteReports(defenderReports));
        assertTrue(reportIdsOf(defender).isEmpty());
    }

    @Test
    void refusesTooManyAndLoggedOut() {
        World world = worlds.createWorld("report-delete-limits", 1);
        WorldContext.set(world.getId());
        AccountContext.set(account("player"));
        List<Long> tooMany = Collections.nCopies(GameFacade.MAX_DELETE + 1, 1L);
        assertThrows(IllegalArgumentException.class, () -> facade.deleteReports(tooMany));
        AccountContext.clear();
        assertThrows(AuthService.AuthException.class, () -> facade.deleteReports(List.of(1L)));
    }
}
