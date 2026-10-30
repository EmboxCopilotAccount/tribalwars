package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.StationedTroopsRepository;
import se.oscarwiklund.twlan2.backend.web.dto.AdminDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// #130: a village that falls to the barbarians in place (abandoned, or its player removed with "barbarian") loses its
// armies stationed elsewhere, like a conquered one, and the support it hosted goes home. They used to stay stationed,
// counted against nobody's farm.
@SpringBootTest
@Transactional
class AbandonedSupportTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired SupportService support;
    @Autowired StationedTroopsRepository stationed;
    @Autowired MovementRepository movements;
    @Autowired AbandonmentService abandonment;
    @Autowired AdminService admin;
    @Autowired StationedOrphanMigration migration;

    World world;
    Account gone, friend;
    Village goneVillage, friendVillage;

    private Account account(String name, Instant lastLogin) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        a.setLastLoginAt(lastLogin);
        return accounts.save(a);
    }

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("abandon-support-test", 1);
        world.getSettings().put("leftVillagesGrow", "60");
        gone = account("gone", Instant.now().minus(2, ChronoUnit.DAYS));
        friend = account("friend", Instant.now());
        goneVillage = worlds.joinWorld(world, gone);
        friendVillage = worlds.joinWorld(world, friend);
        support.station(goneVillage, friendVillage, Map.of(UnitType.SPEAR, 100)); // its army abroad
        support.station(friendVillage, goneVillage, Map.of(UnitType.SWORD, 20));  // a guest in it
    }

    private void assertArmiesSorted() {
        assertTrue(stationed.findByOriginVillage(goneVillage).isEmpty(), "its army abroad is lost with it");
        assertTrue(stationed.findByHostVillage(goneVillage).isEmpty(), "the guest left");
        assertTrue(movements.findAll().stream().anyMatch(m -> m.getType() == MovementType.RETURN
                && m.getTargetVillage().getId().equals(friendVillage.getId())
                && m.getUnits().getOrDefault(UnitType.SWORD, 0) == 20), "and is walking home");
    }

    @Test
    void anAbandonedVillagesArmiesAreSorted() {
        abandonment.sweep();
        assertEquals(OwnerType.BARBARIAN, goneVillage.getOwnerType());
        assertArmiesSorted();
    }

    @Test
    void aRemovedPlayersVillagesTurnedBarbarianAreSorted() {
        admin.removePlayer(world.getId(), gone.getId(), new AdminDto.RemovePlayerRequest("barbarian", null));
        assertArmiesSorted();
    }

    // what earlier abandonments left behind: the army abroad goes once; support stationed IN the barbarian village stays
    // (with open support targets it may be there on purpose)
    @Test
    void theMigrationRemovesArmiesOfOwnerlessVillages() {
        goneVillage.setOwner(null);
        goneVillage.setOwnerType(OwnerType.BARBARIAN); // abandoned the old way, armies untouched
        assertEquals(1, migration.sortOrphans()); // run() only once per database (its mark is set when the test context starts)
        assertTrue(stationed.findByOriginVillage(goneVillage).isEmpty());
        assertEquals(1, stationed.findByHostVillage(goneVillage).size());
    }
}
