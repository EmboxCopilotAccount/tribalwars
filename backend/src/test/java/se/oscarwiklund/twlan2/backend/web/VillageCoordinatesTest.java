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
import se.oscarwiklund.twlan2.backend.service.CombatService;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.web.dto.ReportDto;
import se.oscarwiklund.twlan2.backend.web.dto.VillageStateDto;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Commands and reports must carry the coordinates of their own villages: names repeat
// (every barbarian village is an "Abandoned Camp"), so the frontend can't look them up by name.
@SpringBootTest
@Transactional
class VillageCoordinatesTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired CombatService combat;
    @Autowired GameFacade facade;

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    @Test
    void attackOnSecondAbandonedCampKeepsItsOwnCoordinates() {
        World world = worlds.createWorld("coords-test", 1);
        Account player = new Account();
        player.setUsername("coordtester");
        player.setPasswordHash("x");
        accounts.save(player);
        AccountContext.set(player);
        WorldContext.set(world.getId());

        Village home = worlds.joinWorld(world, player); // also places the first "Abandoned Camp"
        Village camp = new Village();
        camp.setName("Abandoned Camp");
        camp.setWorld(world);
        camp.setOwnerType(OwnerType.BARBARIAN);
        camp.setX(home.getX() < 500 ? 900 : 100);
        camp.setY(home.getY() < 500 ? 950 : 50);
        camp.setResourcesSettledAt(Instant.now());
        villages.save(camp);

        Movement attack = new Movement();
        attack.setOriginVillage(home);
        attack.setTargetVillage(camp);
        attack.setType(MovementType.ATTACK);
        Map<UnitType, Integer> units = new EnumMap<>(UnitType.class);
        units.put(UnitType.AXE, 50);
        attack.setUnits(units);
        attack.setDepartedAt(Instant.now());
        attack.setArrivesAt(Instant.now().plusSeconds(3600));
        movements.save(attack);

        VillageStateDto.MovementDto out = facade.toDto(home).outgoingMovements().get(0);
        assertEquals(camp.getX(), out.otherX());
        assertEquals(camp.getY(), out.otherY());

        combat.resolve(attack);
        ReportDto report = facade.listReports().get(0);
        assertEquals(home.getX(), report.attackerX());
        assertEquals(home.getY(), report.attackerY());
        assertEquals(camp.getX(), report.defenderX());
        assertEquals(camp.getY(), report.defenderY());
        assertEquals(camp.getId(), report.defenderVillageId());
    }
}
