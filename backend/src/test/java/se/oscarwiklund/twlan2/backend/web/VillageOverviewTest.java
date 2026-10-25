package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.SupportService;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.web.dto.OverviewDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Overviews list every village of the player with its buildings, troops (home, support, away, on the road),
// commands and incoming movements - and nothing of anybody else's villages.
@SpringBootTest
@Transactional
class VillageOverviewTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired SupportService support;
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

    private void move(Village from, Village to, MovementType type, Map<UnitType, Integer> units) {
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(type);
        m.getUnits().putAll(units);
        m.setDepartedAt(Instant.now().minusSeconds(60));
        m.setArrivesAt(Instant.now().plusSeconds(3600));
        movements.save(m);
    }

    @Test
    void listsEveryOwnVillageWithTroopsAndMovements() {
        World world = worlds.createWorld("overview-test", 1);
        WorldContext.set(world.getId());
        Account me = account("player");
        Account enemy = account("enemy");
        Village home = worlds.joinWorld(world, me);
        Village second = worlds.joinWorld(world, account("former-owner"));
        second.setOwner(me);
        villages.save(second);
        Village theirs = worlds.joinWorld(world, enemy);

        support.station(home, second, Map.of(UnitType.SCOUT, 10));
        move(home, theirs, MovementType.ATTACK, Map.of(UnitType.AXE, 5));
        move(theirs, home, MovementType.RETURN, Map.of(UnitType.SPEAR, 3)); // troops on their way home
        move(theirs, home, MovementType.ATTACK, Map.of(UnitType.AXE, 99));

        AccountContext.set(me);
        List<OverviewDto> rows = facade.villageOverview();
        assertEquals(2, rows.size());
        OverviewDto h = rows.stream().filter(r -> r.id().equals(home.getId())).findFirst().orElseThrow();
        OverviewDto s = rows.stream().filter(r -> r.id().equals(second.getId())).findFirst().orElseThrow();

        assertEquals(10, h.away().get("SCOUT"));
        assertEquals(10, s.support().get("SCOUT"));
        assertEquals(5, h.moving().get("AXE"));
        assertEquals(3, h.moving().get("SPEAR"));
        assertEquals(List.of("ATTACKING", "RETURNING"), h.commands().stream().map(m -> m.type()).sorted().toList());
        assertEquals(1, h.incoming().size());
        assertEquals("INCOMING_ATTACK", h.incoming().get(0).type());
        assertTrue(h.incoming().get(0).units().isEmpty(), "an enemy's troops are not shown");
        assertTrue(s.commands().isEmpty() && s.incoming().isEmpty());
        assertTrue(h.buildings().containsKey("HEADQUARTERS"));
        assertTrue(h.points() > 0);

        AccountContext.set(enemy);
        assertEquals(List.of(theirs.getId()), facade.villageOverview().stream().map(OverviewDto::id).toList());
    }

    @Test
    void needsALoggedInPlayer() {
        World world = worlds.createWorld("overview-test-anon", 1);
        WorldContext.set(world.getId());
        AccountContext.clear();
        assertThrows(AuthService.AuthException.class, () -> facade.villageOverview());
    }
}
