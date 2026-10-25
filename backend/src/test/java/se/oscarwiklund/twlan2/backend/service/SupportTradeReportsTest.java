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

// Support and merchants leave reports like battles do: one for each real player involved, one in all between a
// player's own villages, none for NPCs.
@SpringBootTest
@Transactional
class SupportTradeReportsTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MovementRepository movements;
    @Autowired TransportRepository transports;
    @Autowired CombatReportRepository reports;
    @Autowired SupportService support;
    @Autowired MarketService market;
    @Autowired ReportService reportService;
    @Autowired TickService tick;

    World world;
    Account me;
    Village home;
    Village second;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("support-trade-reports", 1);
        me = account("player", false);
        home = worlds.joinWorld(world, me);
        second = worlds.joinWorld(world, account("former-owner", false));
        second.setOwner(me);
        villages.save(second);
    }

    private Account account(String name, boolean npc) {
        Account a = new Account();
        a.setUsername(name + "-" + System.nanoTime());
        a.setPasswordHash("x");
        a.setNpc(npc);
        return accounts.save(a);
    }

    private List<CombatReport> reportsOf(Account a) {
        return reports.findByWorldIdAndAccountIdOrderByOccurredAtDesc(world.getId(), a.getId());
    }

    @Test
    void supportBetweenOwnVillagesArrivesAndIsWithdrawn() {
        Movement m = new Movement();
        m.setOriginVillage(home);
        m.setTargetVillage(second);
        m.setType(MovementType.SUPPORT);
        m.getUnits().putAll(Map.of(UnitType.SCOUT, 25));
        m.setDepartedAt(Instant.now().minusSeconds(600));
        m.setArrivesAt(Instant.now().minusSeconds(1));
        movements.save(m);
        tick.tick();

        List<CombatReport> mine = reportsOf(me);
        assertEquals(1, mine.size());
        CombatReport arrived = mine.get(0);
        assertEquals("SUPPORT", ReportService.category(arrived.getDetail()));
        assertEquals("SUPPORT_ARRIVED", ReportService.event(arrived.getDetail()));
        assertEquals(25, arrived.getDetail().get("att:SCOUT"));
        assertEquals(home.getId().intValue(), arrived.getDetail().get("att_village"));
        assertEquals(second.getId().intValue(), arrived.getDetail().get("def_village"));
        assertNull(arrived.getOutcome());

        StationedTroops army = support.stationedFrom(home).get(0);
        support.withdraw(me, army.getId(), Map.of(UnitType.SCOUT, 10));
        List<CombatReport> after = reportsOf(me);
        assertEquals(2, after.size());
        assertTrue(after.stream().anyMatch(r -> "SUPPORT_WITHDRAWN".equals(ReportService.event(r.getDetail()))
                && Integer.valueOf(10).equals(r.getDetail().get("att:SCOUT"))));
    }

    @Test
    void merchantsDeliveringToAnotherPlayerReportToBoth() {
        Account other = account("trader", false);
        Village theirs = worlds.joinWorld(world, other);
        Transport t = new Transport();
        t.setWorldId(world.getId());
        t.setOriginVillageId(home.getId());
        t.setTargetVillageId(theirs.getId());
        t.setOutWood(300);
        t.setOutIron(50);
        t.setBackClay(200); // an accepted offer: the merchants bring clay home
        t.setMerchants(1);
        t.setDepartedAt(Instant.now().minusSeconds(600));
        t.setArrivesAt(Instant.now().minusSeconds(1));
        transports.save(t);
        market.process(Instant.now());

        List<CombatReport> sent = reportsOf(me);
        List<CombatReport> got = reportsOf(other);
        assertEquals(1, sent.size());
        assertEquals(1, got.size());
        assertFalse(sent.get(0).isDefenderView());
        assertTrue(got.get(0).isDefenderView());
        for (CombatReport r : List.of(sent.get(0), got.get(0))) {
            assertEquals("TRADE", ReportService.category(r.getDetail()));
            assertEquals(300, r.getLootWood());
            assertEquals(0, r.getLootClay());
            assertEquals(50, r.getLootIron());
            assertEquals(200, r.getDetail().get("back_clay"));
        }
    }

    @Test
    void supportBetweenPlayersReportsToBothButNotToNpcs() {
        Account mate = account("mate", false);
        Village hers = worlds.joinWorld(world, mate);
        reportService.support(ReportService.SUPPORT_SENT_BACK, home, hers, Map.of(UnitType.SPEAR, 40));
        assertEquals(1, reportsOf(me).size());
        assertEquals(1, reportsOf(mate).size());
        assertTrue(reportsOf(mate).get(0).isDefenderView());

        Account npc = account("npc", true);
        Village npcVillage = worlds.joinWorld(world, npc);
        reportService.support(ReportService.SUPPORT_ARRIVED, home, npcVillage, Map.of(UnitType.SPEAR, 5));
        assertEquals(2, reportsOf(me).size());
        assertTrue(reportsOf(npc).isEmpty());
    }
}
