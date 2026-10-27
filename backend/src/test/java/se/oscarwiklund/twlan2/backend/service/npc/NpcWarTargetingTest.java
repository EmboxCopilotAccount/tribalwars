package se.oscarwiklund.twlan2.backend.service.npc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import se.oscarwiklund.twlan2.backend.service.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

// #118: NPCs only scout and attack real players they are at war with; at war every village of the enemy is a target,
// the close ones first, scouting gives up after failed runs, and a village too weak to win saves up or attacks in full.
@SpringBootTest
@Transactional
class NpcWarTargetingTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired MovementService movementService;
    @Autowired UnitStockRepository stocks;
    @Autowired TribeService tribes;
    @Autowired WorldPoints worldPoints;
    @Autowired PlayerWarService playerWars;
    @Autowired NpcIntelService intel;
    @Autowired NpcIntelRepository intelRows;
    @Autowired NpcLogRepository npcLogs;
    @Autowired NpcMilitary military;

    // nextDouble() always answers the same: 0.999 fails every mood roll, 0 passes it
    static class Fixed extends Random {
        private final double v;
        Fixed(double v) { this.v = v; }
        @Override public double nextDouble() { return v; }
    }

    static final Random NO_MOOD = new Fixed(0.999);

    World world;
    Account npc, human;
    Village npcHome, humanHome;
    final Map<Long, Map<UnitType, Integer>> armies = new HashMap<>();

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("npc-war-test", 1);
        world.getSettings().put("beginnerProtection", "0");
        world.getSettings().put("morale", "off");
        world.getSettings().put("nightStart", "00:00"); // no night: the plans do not depend on the test's clock
        world.getSettings().put("nightEnd", "00:00");
        npc = account("npc", true);
        human = account("human", false);
        npcHome = village(npc, 100, 100);
        humanHome = village(human, 103, 100);
        arm(npcHome, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50));
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
        v.setName(owner.getUsername() + " " + x + "|" + y);
        v.setWorld(world);
        v.setOwner(owner);
        v.setOwnerType(OwnerType.PLAYER);
        v.setX(x);
        v.setY(y);
        v.setResourcesSettledAt(Instant.now());
        return villages.save(v);
    }

    private void arm(Village v, Map<UnitType, Integer> units) {
        armies.put(v.getId(), units);
        restock(v);
    }

    private void restock(Village v) {
        for (var e : armies.getOrDefault(v.getId(), Map.of()).entrySet()) {
            UnitStock s = stocks.findByVillageAndType(v, e.getKey()).orElseGet(() -> {
                UnitStock n = new UnitStock();
                n.setVillage(v);
                n.setType(e.getKey());
                return n;
            });
            s.setCount(e.getValue());
            stocks.save(s);
        }
    }

    // One military step of the village with a fresh world view; returns what it sent (then called back, troops home again).
    private List<Movement> step(Village v, NpcDifficulty d, Random rnd) {
        WorldView view = WorldView.build(world, villages, movements, tribes, worldPoints);
        view.wars(playerWars.enemiesByNpc(world));
        military.step(v, NpcArchetype.BALANCED, d.skill(), d, view, rnd);
        List<Movement> sent = movements.findAll().stream().filter(m -> m.getOriginVillage().getId().equals(v.getId())).toList();
        movements.deleteAll(sent);
        restock(v);
        return sent;
    }

    private static boolean scoutsOnly(Movement m) {
        return m.getUnits().keySet().equals(Set.of(UnitType.SCOUT));
    }

    private static List<Movement> to(List<Movement> sent, Village t) {
        return sent.stream().filter(m -> m.getTargetVillage().getId().equals(t.getId())).toList();
    }

    @Test
    void withoutAWarNpcsLeaveRealPlayersAlone() {
        Account rival = account("rival", true);
        Village rivalHome = village(rival, 97, 100);
        List<Movement> sent = step(npcHome, NpcDifficulty.BRUTAL, new Fixed(0)); // in the mood for a raid
        assertEquals(1, to(sent, rivalHome).size()); // it does go out: to the NPC
        assertTrue(to(sent, humanHome).isEmpty());
    }

    @Test
    void atWarItScoutsFirstWithoutWaitingForItsMood() {
        playerWars.declare(human, npc.getUsername(), world);
        List<Movement> sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(1, sent.size());
        assertEquals(humanHome.getId(), sent.get(0).getTargetVillage().getId());
        assertEquals(Map.of(UnitType.SCOUT, 15), sent.get(0).getUnits()); // max(4 + 6 * 0.6, 1.5 * 8 + 3)
    }

    // An NPC with several villages: in one NPC tick each village took its turn before any scouts came back, so every one
    // of them sent a party (all of them dying meant a burst of failed runs before the pause could stop it).
    @Test
    void oneScoutingPartyAtATimePerTarget() {
        playerWars.declare(human, npc.getUsername(), world);
        Village second = village(npc, 100, 103), third = village(npc, 97, 100);
        arm(second, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50));
        arm(third, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50));
        WorldView view = WorldView.build(world, villages, movements, tribes, worldPoints);
        view.wars(playerWars.enemiesByNpc(world));
        for (Village v : List.of(npcHome, second, third)) military.step(v, NpcArchetype.BALANCED, NpcDifficulty.NORMAL.skill(), NpcDifficulty.NORMAL, view, NO_MOOD);
        long parties = movements.findAll().stream().filter(m -> m.getTargetVillage().getId().equals(humanHome.getId()) && scoutsOnly(m)).count();
        assertEquals(1, parties);
    }

    @Test
    void failedScoutingIsRetriedDoubleThenPausedThenCountedAfresh() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.scoutsLost(world, npc, humanHome, 15);
        List<Movement> sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(Map.of(UnitType.SCOUT, 30), sent.get(0).getUnits()); // twice the failed run

        intel.scoutsLost(world, npc, humanHome, 30); // two in a row: scouting this village pauses
        NpcIntel row = intelRows.findByAccountIdAndTargetVillageId(npc.getId(), humanHome.getId()).orElseThrow();
        assertNotNull(row.getScoutPausedUntil());
        assertTrue(row.getScoutPausedUntil().isAfter(Instant.now().plusSeconds(23 * 3600)));
        sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(1, sent.size());
        assertFalse(scoutsOnly(sent.get(0))); // it attacks on a guess instead

        row.setScoutPausedUntil(Instant.now().minusSeconds(1)); // the pause is over
        sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(Map.of(UnitType.SCOUT, 15), sent.get(0).getUnits());
        row = intelRows.findByAccountIdAndTargetVillageId(npc.getId(), humanHome.getId()).orElseThrow();
        assertEquals(0, row.getFailedScouts());
        assertNull(row.getScoutPausedUntil());
    }

    @Test
    void aRetryItCannotDoubleBecomesAnAttackOnAGuess() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.scoutsLost(world, npc, humanHome, 30); // a retry needs 60, it has 50
        List<Movement> sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(1, sent.size());
        assertFalse(scoutsOnly(sent.get(0)));
    }

    @Test
    void afterLostBattlesItGuessesHigherInsteadOfScouting() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.battle(world, npc, humanHome, false, 0, 0);
        intel.battle(world, npc, humanHome, false, 0, 0);
        List<Movement> sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(1, sent.size());
        assertFalse(scoutsOnly(sent.get(0)));
        var log = npcLogs.recent(world.getId(), "RAID", npc.getId(), PageRequest.of(0, 1));
        assertTrue(log.get(0).getMessage().contains("war, guess after 2 losses"), log.get(0).getMessage());
    }

    @Test
    void everyVillageOfTheEnemyIsATargetTheCloseOnesFirst() {
        Village far = village(human, 100, 160);
        playerWars.declare(human, npc.getUsername(), world);
        intel.battle(world, npc, humanHome, true, 0, 0); // both seen empty
        intel.battle(world, npc, far, true, 0, 0);
        List<Movement> sent = step(npcHome, NpcDifficulty.BRUTAL, NO_MOOD); // skill 1: the best target
        assertEquals(1, to(sent, humanHome).size());

        villages.delete(humanHome);
        sent = step(npcHome, NpcDifficulty.BRUTAL, NO_MOOD);
        assertEquals(1, to(sent, far).size()); // 60 fields away, far out of its raiding range
    }

    @Test
    void theNpcsTribeJoinsTheWarAndOthersStayOut() {
        Account mate = account("mate", true), stranger = account("stranger", true);
        Village mateHome = village(mate, 100, 150), strangerHome = village(stranger, 106, 100);
        arm(mateHome, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50));
        arm(strangerHome, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50));
        Tribe t = tribes.found(npc, world, "War Band", "WB");
        tribes.join(mate, world, t);
        playerWars.declare(human, npc.getUsername(), world);
        intel.battle(world, mate, humanHome, true, 0, 0);
        intel.battle(world, stranger, humanHome, true, 0, 0);

        assertEquals(1, to(step(mateHome, NpcDifficulty.NORMAL, NO_MOOD), humanHome).size()); // 50 fields away
        assertTrue(step(strangerHome, NpcDifficulty.NORMAL, NO_MOOD).isEmpty());
    }

    @Test
    void aPendingWarCountsButNotOnWorldsWithoutNpcAttacks() {
        arm(humanHome, Map.of(UnitType.SCOUT, 5));
        movementService.sendAttack(humanHome, npcHome, Map.of(UnitType.SCOUT, 1));
        assertTrue(playerWars.standing(human, npc, world).war().isPending());

        assertEquals(1, to(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD), humanHome).size());
        assertTrue(step(npcHome, NpcDifficulty.OFF, new Fixed(0)).isEmpty());
    }

    @Test
    void tooWeakNearbyItSavesUpThenAttacksInFull() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.spy(world, npc, humanHome, 3, Map.of(UnitType.SPEAR, 50000, UnitType.SWORD, 50000), 0, 0, 0, 0, 15);
        assertTrue(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD).isEmpty());
        assertTrue(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD).isEmpty());
        var saving = npcLogs.recent(world.getId(), "SAVING", npc.getId(), PageRequest.of(0, 5));
        assertEquals(1, saving.size()); // noted once
        assertTrue(saving.get(0).getMessage().contains("saving for war on " + humanHome.getName()));

        // about 1.5 times its strength: no safe win, but a full assault does real damage
        intel.spy(world, npc, humanHome, 3, Map.of(UnitType.SPEAR, 2500, UnitType.SWORD, 1000), 0, 0, 0, 0, 15);
        List<Movement> sent = step(npcHome, NpcDifficulty.NORMAL, NO_MOOD);
        assertEquals(1, sent.size());
        assertEquals(Map.of(UnitType.AXE, 900, UnitType.LIGHT, 270), sent.get(0).getUnits()); // the whole war army (90 %)
    }

    // #123: a village too strong to attack is not scouted again each time the report goes stale while the NPC saves up;
    // only once its army has grown (or a world day has passed) it looks again.
    @Test
    void savingUpItDoesNotScoutTheSameVillageAgainAndAgain() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.spy(world, npc, humanHome, 3, Map.of(UnitType.SPEAR, 50000, UnitType.SWORD, 50000), 0, 0, 0, 0, 15);
        assertTrue(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD).isEmpty()); // too strong: saving up

        NpcIntel row = intelRows.findByAccountIdAndTargetVillageId(npc.getId(), humanHome.getId()).orElseThrow();
        assertNotNull(row.getSavingPower());
        row.setSeenAt(Instant.now().minusSeconds(3 * 3600)); // the report is stale now
        intelRows.save(row);
        assertTrue(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD).isEmpty()); // still saving, no scouts

        arm(npcHome, Map.of(UnitType.AXE, 1500, UnitType.LIGHT, 300, UnitType.SCOUT, 50)); // the army has grown
        List<Movement> sent = to(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD), humanHome);
        assertEquals(1, sent.size());
        assertTrue(scoutsOnly(sent.get(0))); // a fresh look
    }

    // The battle is fought when the troops arrive: a night starting while they are on the road counts (the plan used the
    // hour it was made at, so a village that won by day was attacked at night with too little).
    @Test
    void itPlansWithTheNightBonusAtTheArrivalTime() {
        playerWars.declare(human, npc.getUsername(), world);
        intel.spy(world, npc, humanHome, 3, Map.of(UnitType.SPEAR, 300), 0, 0, 0, 0, 0);
        List<Movement> byDay = to(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD), humanHome);
        assertEquals(1, byDay.size());

        java.time.format.DateTimeFormatter hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        world.getSettings().put("nightStart", java.time.LocalTime.now().plusMinutes(10).format(hhmm)); // day now, night on arrival
        world.getSettings().put("nightEnd", java.time.LocalTime.now().plusHours(6).format(hhmm));
        world.getSettings().put("nightBonus", "2");
        List<Movement> atNight = to(step(npcHome, NpcDifficulty.NORMAL, NO_MOOD), humanHome);
        assertEquals(1, atNight.size());
        assertTrue(atNight.get(0).getArrivesAt().isAfter(Instant.now().plusSeconds(11 * 60)));
        int day = byDay.get(0).getUnits().values().stream().mapToInt(Integer::intValue).sum();
        int night = atNight.get(0).getUnits().values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(night > day, night + " troops at night vs " + day + " by day");
    }

    @Test
    void noblemenGoForRealPlayersOnlyAtWar() {
        arm(npcHome, Map.of(UnitType.AXE, 1000, UnitType.LIGHT, 300, UnitType.SCOUT, 50, UnitType.SNOB, 5));
        intel.battle(world, npc, humanHome, true, 0, 0);
        assertTrue(to(step(npcHome, NpcDifficulty.BRUTAL, new Fixed(0)), humanHome).isEmpty());

        playerWars.declare(human, npc.getUsername(), world);
        List<Movement> sent = to(step(npcHome, NpcDifficulty.BRUTAL, NO_MOOD), humanHome);
        assertEquals(4, sent.size()); // loyalty 100 at 20-35 a nobleman
        for (Movement m : sent) assertEquals(1, m.getUnits().get(UnitType.SNOB));
    }
}
