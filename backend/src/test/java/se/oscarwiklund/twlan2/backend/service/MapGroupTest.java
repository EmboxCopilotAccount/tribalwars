package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// #119: map groups colour players, tribes and villages; each player only sees and edits their own.
@SpringBootTest
@Transactional
class MapGroupTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired TribeService tribes;
    @Autowired MapGroupService mapGroups;

    World world;
    Account me, other;
    Village otherVillage;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("mapgroup-test", 1);
        me = account("mg-me-" + System.nanoTime());
        other = account("mg-other-" + System.nanoTime());
        worlds.joinWorld(world, me);
        otherVillage = worlds.joinWorld(world, other);
    }

    private Account account(String name) {
        Account a = new Account();
        a.setUsername(name);
        a.setPasswordHash("x");
        return accounts.save(a);
    }

    private MapGroupService.Group only(List<MapGroupService.Group> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    @Test
    void addsPlayerTribeAndVillageAndListsThemResolved() {
        Tribe t = tribes.found(other, world, "Map Wolves", "MW");
        Long id = only(mapGroups.create(me, world, "Targets", "#FF0000")).id();
        mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.PLAYER, other.getUsername().toUpperCase());
        mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.TRIBE, "mw");
        MapGroupService.Group g = only(mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.VILLAGE,
                otherVillage.getX() + "|" + otherVillage.getY()));
        assertEquals("#ff0000", g.color());
        assertTrue(g.shown());
        assertEquals(List.of(MapGroupEntry.Kind.PLAYER, MapGroupEntry.Kind.TRIBE, MapGroupEntry.Kind.VILLAGE),
                g.entries().stream().map(MapGroupService.Entry::kind).toList());
        assertEquals(other.getUsername(), g.entries().get(0).name());
        assertEquals(t.getId(), g.entries().get(1).targetId());
        assertEquals("[MW] Map Wolves", g.entries().get(1).label());
        assertEquals(otherVillage.getId(), g.entries().get(2).targetId());

        g = only(mapGroups.removeEntry(me, world, id, g.entries().get(1).id()));
        assertEquals(2, g.entries().size());
        g = only(mapGroups.update(me, world, id, null, "#00ff00", false));
        assertEquals("#00ff00", g.color());
        assertFalse(g.shown());
        assertEquals("Targets", g.name());
        assertTrue(mapGroups.delete(me, world, id).isEmpty());
    }

    @Test
    void refusesBadInput() {
        Long id = only(mapGroups.create(me, world, "Targets", "#123456")).id();
        assertThrows(IllegalArgumentException.class, () -> mapGroups.create(me, world, "targets", "#123456"));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.create(me, world, " ", "#123456"));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.create(me, world, "Bad colour", "red"));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.PLAYER, "no-such-player"));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.PLAYER, me.getUsername()));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.TRIBE, "XX"));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.VILLAGE, "not coords"));
        mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.PLAYER, other.getUsername());
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(me, world, id, MapGroupEntry.Kind.PLAYER, other.getUsername()));
    }

    @Test
    void groupsArePrivate() {
        Long id = only(mapGroups.create(me, world, "Mine", "#123456")).id();
        assertTrue(mapGroups.list(other, world).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mapGroups.update(other, world, id, "Taken", null, null));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.delete(other, world, id));
        assertThrows(IllegalArgumentException.class, () -> mapGroups.addEntry(other, world, id, MapGroupEntry.Kind.PLAYER, me.getUsername()));
        mapGroups.deleteAccountData(me.getId());
        assertTrue(mapGroups.list(me, world).isEmpty());
    }
}
