package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Commands can be named in the sender's and in the target's list; each side only ever sees its own name.
@SpringBootTest
@Transactional
class RenameCommandTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementService movementService;

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    @Test
    void eachSideNamesItsOwnView() {
        World world = worlds.createWorld("rename-test", 1);
        world.getSettings().put("beginnerProtection", "0");
        Account attacker = account("attacker");
        Account defender = account("defender");
        Village from = worlds.joinWorld(world, attacker);
        Village to = worlds.joinWorld(world, defender);
        UnitStock s = new UnitStock();
        s.setVillage(from);
        s.setType(UnitType.AXE);
        s.setCount(10);
        stocks.save(s);
        Movement m = movementService.sendAttack(from, to, Map.of(UnitType.AXE, 5));

        movementService.rename(attacker, m.getId(), false, "  Fake\tone  ");
        movementService.rename(defender, m.getId(), true, "Probably a fake");
        assertEquals("Fake one", m.getLabel());
        assertEquals("Probably a fake", m.getTargetLabel());

        assertThrows(MovementService.MovementException.class, () -> movementService.rename(defender, m.getId(), false, "x"));
        assertThrows(MovementService.MovementException.class, () -> movementService.rename(attacker, m.getId(), true, "x"));

        movementService.rename(attacker, m.getId(), false, "   ");
        assertNull(m.getLabel()); // blank = back to the default name
        assertEquals(MovementService.LABEL_MAX, MovementService.cleanLabel("x".repeat(100)).length());
    }
}
