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

// What happens to troops on the road when a village is conquered.
@SpringBootTest
@Transactional
class ConquestMovementsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired UnitStockRepository stocks;
    @Autowired VillageService villageService;
    @Autowired TickService tick;

    World world;
    Village attackerHome;
    Village target; // a real player's village, conquered in the tests

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("conquest-test", 1);
        attackerHome = worlds.joinWorld(world, account("conqueror"));
        target = worlds.joinWorld(world, account("defender"));
        villageService.setLoyalty(target, 1); // one nobleman takes it
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private Movement send(Village from, Village to, MovementType type, Map<UnitType, Integer> units, Instant arrivesAt) {
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(type);
        m.getUnits().putAll(units);
        m.setDepartedAt(arrivesAt.minusSeconds(600));
        m.setArrivesAt(arrivesAt);
        return movements.save(m);
    }

    private List<Movement> returningTo(Village v) {
        return movements.findByOriginVillageOrTargetVillage(v, v).stream()
                .filter(m -> m.getType() == MovementType.RETURN && m.getTargetVillage().getId().equals(v.getId()))
                .toList();
    }

    private void conquerTarget() {
        send(attackerHome, target, MovementType.ATTACK, Map.of(UnitType.AXE, 500, UnitType.SNOB, 1), Instant.now().minusSeconds(1));
        tick.tick();
        assertEquals(attackerHome.getOwner().getId(), villages.findById(target.getId()).orElseThrow().getOwner().getId());
    }

    @Test
    void anEarlierWaveOnItsWayHomeSurvivesTheConquest() {
        send(attackerHome, target, MovementType.ATTACK, Map.of(UnitType.AXE, 2000), Instant.now().minusSeconds(2));
        tick.tick(); // the clear: its survivors set off home
        assertEquals(1, returningTo(attackerHome).size());

        conquerTarget();

        assertEquals(2, returningTo(attackerHome).size()); // the clear and the noble's escort are both on their way
    }

    @Test
    void theOldOwnersTroopsDoNotJoinTheConqueror() {
        Village camp = new Village();
        camp.setName("Abandoned Camp");
        camp.setWorld(world);
        camp.setOwnerType(OwnerType.BARBARIAN);
        camp.setX(target.getX() < 500 ? target.getX() + 3 : target.getX() - 3);
        camp.setY(target.getY());
        camp.setResourcesSettledAt(Instant.now());
        villages.save(camp);
        send(target, camp, MovementType.ATTACK, Map.of(UnitType.LIGHT, 50), Instant.now().minusSeconds(2));
        tick.tick(); // the defender's raid sets off home
        assertEquals(1, returningTo(target).size());

        conquerTarget();
        for (Movement m : returningTo(target)) m.setArrivesAt(Instant.now().minusSeconds(1));
        tick.tick(); // the raid would arrive now

        int light = stocks.findByVillageAndType(target, UnitType.LIGHT).map(UnitStock::getCount).orElse(0);
        assertEquals(0, light); // the conqueror must not get the old owner's raiders
    }
}
