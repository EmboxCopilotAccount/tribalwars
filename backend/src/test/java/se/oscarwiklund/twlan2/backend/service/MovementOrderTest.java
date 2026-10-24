package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Commands that fall due in the same tick are resolved in arrival order, so a clear that lands
// just before its noblemen fights first. Same arrival time: the one sent first goes first.
@SpringBootTest
@Transactional
class MovementOrderTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;

    @Test
    void dueMovementsComeInArrivalOrder() {
        World world = worlds.createWorld("order-test", 1);
        Account player = new Account();
        player.setUsername("ordertester");
        player.setPasswordHash("x");
        accounts.save(player);
        Village home = worlds.joinWorld(world, player);
        Village camp = new Village();
        camp.setName("Abandoned Camp");
        camp.setWorld(world);
        camp.setOwnerType(OwnerType.BARBARIAN);
        camp.setX(home.getX() < 500 ? 900 : 100);
        camp.setY(home.getY() < 500 ? 950 : 50);
        camp.setResourcesSettledAt(Instant.now());
        villages.save(camp);

        Instant t = Instant.now().minusSeconds(60);
        Movement noble = attack(home, camp, UnitType.SNOB, t.plusMillis(900));
        Movement clear = attack(home, camp, UnitType.AXE, t.plusMillis(200));
        Movement fake = attack(home, camp, UnitType.RAM, t.plusMillis(200));

        List<Long> due = movements.findByArrivesAtLessThanEqualOrderByArrivesAtAscIdAsc(Instant.now()).stream()
                .map(Movement::getId)
                .filter(id -> List.of(noble.getId(), clear.getId(), fake.getId()).contains(id))
                .toList();
        assertEquals(List.of(clear.getId(), fake.getId(), noble.getId()), due);
    }

    private Movement attack(Village from, Village to, UnitType unit, Instant arrivesAt) {
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(MovementType.ATTACK);
        m.getUnits().putAll(Map.of(unit, 1));
        m.setDepartedAt(arrivesAt.minusSeconds(600));
        m.setArrivesAt(arrivesAt);
        return movements.save(m);
    }
}
