package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;

import static org.junit.jupiter.api.Assertions.*;

// #104: a village note belongs to the village's owner; a new owner starts with an empty note.
@SpringBootTest
@Transactional
class VillageNoteTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired ConquestService conquests;
    @Autowired VillageNoteService notes;

    World world;
    Account me, other;
    Village mine;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("note-test", 1);
        me = account("vn-me-" + System.nanoTime());
        other = account("vn-other-" + System.nanoTime());
        mine = worlds.joinWorld(world, me);
        worlds.joinWorld(world, other);
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name);
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    @Test
    void savesReadsAndClears() {
        assertEquals("", notes.get(me, mine));
        assertEquals("line 1\nline 2", notes.save(me, mine, "  line 1\nline 2  "));
        assertEquals("line 1\nline 2", notes.get(me, mine));
        assertEquals("", notes.save(me, mine, "   "));
        assertEquals("", notes.get(me, mine));
        assertThrows(IllegalArgumentException.class, () -> notes.save(me, mine, "x".repeat(VillageNoteService.MAX_LENGTH + 1)));
    }

    @Test
    void onlyTheOwnerAndNotTheNextOne() {
        notes.save(me, mine, "my secret plan");
        assertThrows(IllegalArgumentException.class, () -> notes.get(other, mine));
        assertThrows(IllegalArgumentException.class, () -> notes.save(other, mine, "hijack"));
        conquests.conquer(mine, other);
        assertEquals("", notes.get(other, mine));
        notes.deleteAccountData(me.getId());
    }
}
