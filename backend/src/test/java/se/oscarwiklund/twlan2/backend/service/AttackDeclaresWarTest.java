package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// #118: a player's attack on an NPC (scouts too) declares war when it is sent; calling it off in time calls the war
// off, unless another attack is on its way or one has landed.
@SpringBootTest
@Transactional
class AttackDeclaresWarTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired UnitStockRepository stocks;
    @Autowired MovementRepository movements;
    @Autowired MovementService movementService;
    @Autowired TickService tick;
    @Autowired TribeService tribes;
    @Autowired PlayerWarService wars;
    @Autowired PlayerWarRepository rows;
    @Autowired MailParticipantRepository mailParticipants;
    @Autowired MailThreadRepository mailThreads;

    World world;
    Account human, npc, mate;
    Village humanHome, npcHome, mateHome;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("attack-war-test", 1);
        world.getSettings().put("beginnerProtection", "0");
        human = account("human", false);
        npc = account("npc", true);
        mate = account("mate", true);
        humanHome = village(human, 100, 100);
        npcHome = village(npc, 103, 100);
        mateHome = village(mate, 106, 100);
        stock(humanHome, UnitType.SCOUT, 10);
        stock(humanHome, UnitType.AXE, 100);
        stock(npcHome, UnitType.SCOUT, 10);
        tribes.join(mate, world, tribes.found(npc, world, "Npc Tribe", "NT"));
    }

    private Account account(String name, boolean isNpc) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        a.setNpc(isNpc);
        return accounts.save(a);
    }

    private Village village(Account owner, int x, int y) {
        Village v = new Village();
        v.setName(owner.getUsername());
        v.setWorld(world);
        v.setOwner(owner);
        v.setOwnerType(OwnerType.PLAYER);
        v.setX(x);
        v.setY(y);
        v.setResourcesSettledAt(Instant.now());
        return villages.save(v);
    }

    private void stock(Village v, UnitType type, int n) {
        UnitStock s = new UnitStock();
        s.setVillage(v);
        s.setType(type);
        s.setCount(n);
        stocks.save(s);
    }

    private List<String> mailSubjects(Account a) {
        return mailParticipants.findByAccountId(a.getId()).stream()
                .map(p -> mailThreads.findById(p.getThreadId()).orElseThrow().getSubject()).toList();
    }

    @Test
    void scoutsDeclareAPendingWarOnTheNpcAndItsTribe() {
        movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.SCOUT, 1));
        PlayerWar w = rows.findByWorldIdAndPlayerAccountIdAndNpcAccountId(world.getId(), human.getId(), npc.getId()).orElseThrow();
        assertEquals(PlayerWarService.BY_ATTACK, w.getDeclaredBy());
        assertTrue(w.isPending());
        assertTrue(wars.enemiesByNpc(world).get(mate.getId()).contains(human.getId()));
        assertEquals(List.of("War"), mailSubjects(human));

        movementService.sendAttack(humanHome, mateHome, Map.of(UnitType.SCOUT, 1)); // already at war with the tribe
        assertEquals(1, rows.findByWorldId(world.getId()).size());
    }

    @Test
    void cancellingTheOnlyAttackCallsTheWarOff() {
        Movement m = movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.AXE, 10));
        movementService.cancel(human, m.getId());
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
        assertTrue(mailSubjects(human).contains("Stand down"));
    }

    @Test
    void anotherAttackOnItsWayKeepsTheWar() {
        Movement first = movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.AXE, 10));
        Movement second = movementService.sendAttack(humanHome, mateHome, Map.of(UnitType.AXE, 10)); // a tribe-mate
        movementService.cancel(human, first.getId());
        assertEquals(1, rows.findByWorldId(world.getId()).size());
        movementService.cancel(human, second.getId());
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }

    @Test
    void aLandedAttackMakesItFinal() {
        Movement m = movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.SCOUT, 1));
        m.setArrivesAt(Instant.now().minusSeconds(1));
        tick.tick();
        assertFalse(movements.existsById(m.getId()));
        PlayerWar w = rows.findByWorldIdAndPlayerAccountIdAndNpcAccountId(world.getId(), human.getId(), npc.getId()).orElseThrow();
        assertFalse(w.isPending());

        Movement next = movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.AXE, 10));
        movementService.cancel(human, next.getId());
        assertEquals(1, rows.findByWorldId(world.getId()).size()); // not pending: it stands
    }

    @Test
    void npcsAndTribeMatesDeclareNothing() {
        movementService.sendAttack(npcHome, humanHome, Map.of(UnitType.SCOUT, 1));
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());

        tribes.join(human, world, tribes.tribeOf(npc, world).orElseThrow());
        movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.SCOUT, 1));
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }
}
