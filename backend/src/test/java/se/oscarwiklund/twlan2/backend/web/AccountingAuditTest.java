package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import se.oscarwiklund.twlan2.backend.service.GameLock;
import se.oscarwiklund.twlan2.backend.service.MarketService;
import se.oscarwiklund.twlan2.backend.service.VillageService;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.web.dto.TrainRequest;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Farm and market accounting (#75, #76, #124): every order goes through GameLockFilter and the real controller,
// every check re-reads the database. Not @Transactional, see OrdersChargeTest.
@SpringBootTest
class AccountingAuditTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired BuildingRepository buildings;
    @Autowired TransportRepository transports;
    @Autowired MarketOfferRepository offers;
    @Autowired MarketController marketController;
    @Autowired TrainController trainController;
    @Autowired MarketService market;
    @Autowired VillageService villageService;
    @Autowired TransactionTemplate tx;
    @Autowired GameLockFilter lockFilter;
    @Autowired GameLock gameLock;

    World world;
    Account alice, bob;
    Village a, b;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("accounting-audit-" + System.nanoTime(), 1);
        alice = player("audit-a-");
        bob = player("audit-b-");
        a = worlds.joinWorld(world, alice);
        b = worlds.joinWorld(world, bob);
        for (Village v : new Village[] {a, b}) {
            level(v, BuildingType.WAREHOUSE, 30);
            level(v, BuildingType.MARKET, 10);
            level(v, BuildingType.FARM, 30);
            level(v, BuildingType.BARRACKS, 1);
            resources(v, 20_000);
        }
    }

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    private Account player(String prefix) {
        Account p = new Account();
        p.setUsername(prefix + System.nanoTime());
        p.setPasswordHash("x");
        return accounts.save(p);
    }

    private void level(Village v, BuildingType type, int level) {
        Building bd = buildings.findByVillageAndType(v, type).orElseGet(() -> {
            Building n = new Building();
            n.setVillage(v);
            n.setType(type);
            return n;
        });
        bd.setLevel(level);
        buildings.save(bd);
    }

    private void resources(Village v, double each) {
        Village fresh = villages.findById(v.getId()).orElseThrow();
        fresh.setWood(each);
        fresh.setClay(each);
        fresh.setIron(each);
        fresh.setResourcesSettledAt(Instant.now());
        villages.save(fresh);
    }

    private Village db(Village v) { return villages.findById(v.getId()).orElseThrow(); }

    private void as(Account p) {
        WorldContext.set(world.getId());
        AccountContext.set(p);
    }

    // As an API request: through the lock filter, like the servlet container calls it.
    private void request(Runnable controllerCall) {
        try {
            lockFilter.doFilter(new MockHttpServletRequest("POST", "/api/test"), new MockHttpServletResponse(),
                    (req, res) -> controllerCall.run());
        } catch (java.io.IOException | jakarta.servlet.ServletException e) {
            throw new RuntimeException(e);
        }
    }

    private int days;
    private void tickFarAhead() { // each call is 10 days after the last, so return trips started by the previous one arrive
        Instant at = Instant.now().plusSeconds(86_400L * (days += 10));
        tx.executeWithoutResult(s -> market.process(at));
    }

    @Test
    void sendingTakesFromTheSenderOnceAndDeliversOnce() {
        double before = db(a).getWood();
        double targetBefore = db(b).getWood();
        as(alice);
        request(() -> marketController.send(new MarketController.SendRequest(b.getId(), 1500, 0, 0)));

        assertEquals(before - 1500, db(a).getWood(), 1.0, "sender wood in the database");
        assertEquals(2, market.totalMerchants(db(a)) - market.availableMerchants(db(a)), "merchants out");

        tickFarAhead(); // arrives
        assertEquals(targetBefore + 1500, db(b).getWood(), 2.0, "target wood");
        tickFarAhead(); // merchants home
        assertEquals(before - 1500, db(a).getWood(), 5.0, "sender gets nothing back");
        assertTrue(transports.findByOriginVillageId(a.getId()).isEmpty());
        assertEquals(market.totalMerchants(db(a)), market.availableMerchants(db(a)));
    }

    @Test
    void offersMoveEachResourceExactlyOnce() {
        as(alice);
        double aWood = db(a).getWood(), aClay = db(a).getClay();
        request(() -> marketController.createOffer(new MarketController.OfferRequest("wood", 1000, "clay", 1000, 3, 0)));
        assertEquals(aWood - 3000, db(a).getWood(), 1.0, "offer takes the goods at once");
        Long offerId = offers.findByVillageId(a.getId()).get(0).getId();

        as(bob);
        double bWood = db(b).getWood(), bClay = db(b).getClay();
        request(() -> marketController.accept(offerId, new MarketController.AcceptRequest(1)));
        assertEquals(bClay - 1000, db(b).getClay(), 1.0, "acceptor pays at once");

        tickFarAhead();
        assertEquals(aClay + 1000, db(a).getClay(), 2.0, "offerer receives the payment");
        tickFarAhead();
        assertEquals(bWood + 1000, db(b).getWood(), 5.0, "acceptor receives the goods");

        as(alice);
        request(() -> marketController.cancelOffer(offerId));
        assertEquals(aWood - 1000, db(a).getWood(), 5.0, "cancelling returns the two unsold lots");
    }

    // The game tick writes a village (here: loot of returning troops, TickService.processMovements) while the player's
    // request charges the same village. The tick holds the GameLock like TickService.tick, so the request waits.
    @Test
    void aTickWritingTheSameVillageDoesNotUndoACharge() throws Exception {
        double before = db(a).getWood();
        CountDownLatch loaded = new CountDownLatch(1), go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> tick = pool.submit(() -> tx.executeWithoutResult(s -> {
            gameLock.holdForTransaction();
            Village home = villages.findById(a.getId()).orElseThrow();
            villageService.settleResources(home);
            loaded.countDown();
            try { go.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            home.setWood(home.getWood() + 100); // loot delivered
        }));
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
        Future<?> send = pool.submit(() -> {
            as(alice);
            try {
                request(() -> marketController.send(new MarketController.SendRequest(b.getId(), 1500, 0, 0)));
            } finally {
                AccountContext.clear();
                WorldContext.clear();
            }
        });
        for (int i = 0; i < 100 && !gameLock.hasQueuedThreads(); i++) Thread.sleep(50);
        assertTrue(gameLock.hasQueuedThreads(), "the request waits for the tick");
        go.countDown();
        tick.get(20, TimeUnit.SECONDS);
        send.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(before - 1500 + 100, db(a).getWood(), 2.0, "both the charge and the loot must stick");
    }

    // Several recruit requests at the same moment, each one needing all of the free farm space.
    @Test
    void parallelRecruitingCannotOverfillTheFarm() throws Exception {
        level(a, BuildingType.FARM, 1);
        int free = villageService.populationCapacity(db(a)) - villageService.populationUsed(db(a));
        double woodBefore = db(a).getWood();
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                as(alice);
                try {
                    start.await();
                    request(() -> trainController.train(new TrainRequest("SPEAR", free)));
                    accepted.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    AccountContext.clear();
                    WorldContext.clear();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        int used = villageService.populationUsed(db(a));
        assertEquals(woodBefore - accepted.get() * free * UnitType.SPEAR.woodCost, db(a).getWood(), 5.0,
                "every accepted order is paid for (" + accepted.get() + " accepted)");
        assertEquals(1, accepted.get(), "orders accepted; population " + used + " / " + villageService.populationCapacity(db(a)));
        assertTrue(used <= villageService.populationCapacity(db(a)));
    }
}
