package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// An attack can be called off in its first 10 minutes: the troops walk home as long as they have walked.
@SpringBootTest
@Transactional
class CommandCancelTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired MovementRepository movements;
    @Autowired MovementService movementService;

    Account me;
    Village home;
    Village target;

    @BeforeEach
    void setUp() {
        World world = worlds.createWorld("cancel-test", 1);
        me = account("sender");
        home = worlds.joinWorld(world, me);
        target = worlds.joinWorld(world, account("target"));
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private Movement attackSentSecondsAgo(long seconds) {
        Movement m = new Movement();
        m.setOriginVillage(home);
        m.setTargetVillage(target);
        m.setType(MovementType.ATTACK);
        m.getUnits().putAll(Map.of(UnitType.AXE, 100));
        m.setDepartedAt(Instant.now().minusSeconds(seconds));
        m.setArrivesAt(Instant.now().plusSeconds(3600));
        return movements.save(m);
    }

    @Test
    void theTroopsTurnRound() {
        Movement attack = attackSentSecondsAgo(120);
        Movement back = movementService.cancel(me, attack.getId());

        assertTrue(movements.findById(attack.getId()).isEmpty());
        assertEquals(MovementType.RETURN, back.getType());
        assertEquals(home.getId(), back.getTargetVillage().getId());
        assertEquals(100, back.getUnits().get(UnitType.AXE));
        long home = Duration.between(back.getDepartedAt(), back.getArrivesAt()).toSeconds();
        assertTrue(home >= 119 && home <= 122, "home in " + home + " s"); // as long as they walked
    }

    @Test
    void onlyInTheFirstTenMinutes() {
        Movement attack = attackSentSecondsAgo(MovementService.CANCEL_SECONDS + 5);
        assertThrows(MovementService.MovementException.class, () -> movementService.cancel(me, attack.getId()));
        assertTrue(movements.findById(attack.getId()).isPresent());
    }

    @Test
    void onlyBySender() {
        Movement attack = attackSentSecondsAgo(10);
        assertThrows(MovementService.MovementException.class, () -> movementService.cancel(target.getOwner(), attack.getId()));
    }
}
