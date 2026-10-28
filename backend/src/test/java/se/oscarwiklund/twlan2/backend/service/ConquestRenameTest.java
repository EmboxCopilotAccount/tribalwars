package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MigrationMarkRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.npc.NpcConquestNameMigration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #128: an NPC renames the villages it conquers; the ones it took earlier are renamed once.
@SpringBootTest
@Transactional
class ConquestRenameTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired ConquestService conquests;
    @Autowired NpcConquestNameMigration migration;
    @Autowired MigrationMarkRepository marks;

    World world;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("rename-test", 1);
    }

    private Account account(String name, boolean npc) {
        Account a = new Account();
        a.setUsername(name);
        a.setPasswordHash("x");
        a.setNpc(npc);
        return accounts.save(a);
    }

    private Village playerVillage(String owner) {
        return worlds.joinWorld(world, account(owner + "-" + System.nanoTime(), false));
    }

    private String nameOf(Village v) {
        return villages.findById(v.getId()).orElseThrow().getName();
    }

    @Test
    void npcNumbersItsConquestsAndFillsGaps() {
        Account npc = account("Rowena Thatch", true);
        worlds.joinWorld(world, npc);
        Village a = playerVillage("p1"), b = playerVillage("p2"), c = playerVillage("p3");
        conquests.conquer(a, npc);
        conquests.conquer(b, npc);
        assertEquals("Rowena Thatch's village 2", nameOf(a));
        assertEquals("Rowena Thatch's village 3", nameOf(b));
        conquests.conquer(a, account("taker-" + System.nanoTime(), false)); // number 2 is free again
        conquests.conquer(c, npc);
        assertEquals("Rowena Thatch's village 2", nameOf(c));
    }

    @Test
    void playerKeepsTheNameAndNpcRetakingItsOwnKeepsIt() {
        Account npc = account("Osgar Venn", true);
        Village home = worlds.joinWorld(world, npc);
        Village v = playerVillage("p");
        String before = nameOf(v);
        conquests.conquer(v, account("player-" + System.nanoTime(), false));
        assertEquals(before, nameOf(v));
        conquests.conquer(home, npc);
        assertEquals("Osgar Venn's village", nameOf(home));
    }

    @Test
    void longNpcNameFits() {
        Account npc = account("Bartholomew Featherstonehaugh-Smythe", true);
        Village v = playerVillage("p");
        conquests.conquer(v, npc);
        String name = nameOf(v);
        assertTrue(name.length() <= 32, name);
        assertTrue(name.endsWith("'s village"), name);
    }

    @Test
    void migrationRenamesEarlierConquestsOnce() {
        Account npc = account("Wendel Pike", true);
        Village home = worlds.joinWorld(world, npc);
        Village taken = playerVillage("p"), camp = playerVillage("q");
        Village player = playerVillage("r");
        String playerName = nameOf(player);
        for (Village v : new Village[] {taken, camp}) { // conquered the old way: name unchanged
            v.setOwner(npc);
            villages.save(v);
        }
        camp.setName("Abandoned Camp");
        villages.save(camp);
        marks.deleteById("npc-village-names-1"); // it ran when the test context started
        migration.run();
        assertEquals("Wendel Pike's village", nameOf(home));
        assertEquals("Wendel Pike's village 2", nameOf(taken));
        assertEquals("Wendel Pike's village 3", nameOf(camp));
        assertEquals(playerName, nameOf(player));

        taken.setName("Old name");
        villages.save(taken);
        migration.run(); // marked done: nothing changes
        assertEquals("Old name", nameOf(taken));
    }
}
