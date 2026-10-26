package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.PlayerWarRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// #118: a player declares war on an NPC, which brings in the NPC members of its tribe; peace only after warPeaceHours.
@SpringBootTest
@Transactional
class PlayerWarTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired TribeService tribes;
    @Autowired PlayerWarService wars;
    @Autowired PlayerWarRepository rows;

    World world;
    Account human, npc, mate;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("player-war-test", 1);
        human = account("human", false);
        npc = account("npc", true);
        mate = account("mate", true);
        village(human, 100, 100);
        village(npc, 110, 100);
        village(mate, 120, 100);
        Tribe t = tribes.found(npc, world, "Npc Tribe", "NT");
        tribes.join(mate, world, t);
    }

    private Account account(String name, boolean isNpc) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        a.setNpc(isNpc);
        return accounts.save(a);
    }

    private void village(Account owner, int x, int y) {
        Village v = new Village();
        v.setName(owner.getUsername());
        v.setWorld(world);
        v.setOwner(owner);
        v.setOwnerType(OwnerType.PLAYER);
        v.setX(x);
        v.setY(y);
        v.setResourcesSettledAt(Instant.now());
        villages.save(v);
    }

    @Test
    void aWarOnAnNpcIsAWarOnItsTribe() {
        PlayerWar w = wars.declare(human, npc.getUsername(), world);
        assertEquals(PlayerWarService.BY_PLAYER, w.getDeclaredBy());
        assertFalse(w.isPending());

        var enemies = wars.enemiesByNpc(world);
        assertEquals(Set.of(human.getId()), enemies.get(npc.getId()));
        assertEquals(Set.of(human.getId()), enemies.get(mate.getId()));

        var s = wars.standing(human, mate, world);
        assertTrue(s.viaTribe(mate));
        assertEquals(npc.getId(), s.declaredOn().getId());
        var e = assertThrows(IllegalArgumentException.class, () -> wars.declare(human, mate.getUsername(), world));
        assertTrue(e.getMessage().contains("through its tribe"));
        e = assertThrows(IllegalArgumentException.class, () -> wars.makePeace(human, mate.getUsername(), world));
        assertTrue(e.getMessage().contains("make peace with " + npc.getUsername()));
    }

    @Test
    void onlyNpcsOutsideYourTribeAndOnlyOnce() {
        Account other = account("other", false);
        village(other, 130, 100);
        assertThrows(IllegalArgumentException.class, () -> wars.declare(human, other.getUsername(), world));
        wars.declare(human, npc.getUsername(), world);
        assertThrows(IllegalArgumentException.class, () -> wars.declare(human, npc.getUsername(), world));

        Account member = account("member", false);
        village(member, 140, 100);
        tribes.join(member, world, tribes.tribeOf(npc, world).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> wars.declare(member, npc.getUsername(), world));
        assertNull(wars.standing(member, npc, world));
    }

    @Test
    void peaceOnlyAfterTheCooldown() {
        PlayerWar w = wars.declare(human, npc.getUsername(), world);
        var e = assertThrows(PlayerWarService.PeaceTooEarlyException.class, () -> wars.makePeace(human, npc.getUsername(), world));
        assertEquals(w.getStartedAt().plus(Duration.ofHours(24)), e.peaceFrom());
        assertNotNull(wars.standing(human, npc, world));

        w.setStartedAt(Instant.now().minus(Duration.ofHours(24)).minusSeconds(1));
        wars.makePeace(human, npc.getUsername(), world);
        assertNull(wars.standing(human, npc, world));
        assertTrue(wars.enemiesByNpc(world).isEmpty());
    }

    @Test
    void theWorldSetsTheCooldown() {
        world.getSettings().put("warPeaceHours", "0");
        wars.declare(human, npc.getUsername(), world);
        wars.makePeace(human, npc.getUsername(), world);
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }

    @Test
    void leavingTheWorldEndsItsWars() {
        wars.declare(human, npc.getUsername(), world);
        wars.leaveWorld(world.getId(), npc.getId());
        assertTrue(rows.findByWorldId(world.getId()).isEmpty());
    }
}
