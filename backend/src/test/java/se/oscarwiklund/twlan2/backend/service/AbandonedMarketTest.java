package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MarketOfferRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A village that falls to the barbarians takes its market offers with it.
@SpringBootTest
@Transactional
class AbandonedMarketTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired VillageRepository villages;
    @Autowired MarketOfferRepository offers;
    @Autowired AbandonmentService abandonment;

    @Test
    void anAbandonedVillagesOffersAreGone() {
        World world = worlds.createWorld("abandon-market-test", 1);
        world.getSettings().put("leftVillagesGrow", "60");
        Account a = new Account();
        a.setUsername("gone-" + System.nanoTime());
        a.setPasswordHash("x");
        a.setLastLoginAt(Instant.now().minus(2, ChronoUnit.DAYS));
        a = accounts.save(a);
        Village v = worlds.joinWorld(world, a);

        MarketOffer o = new MarketOffer();
        o.setWorldId(world.getId());
        o.setVillageId(v.getId());
        o.setSellResource(Resource.WOOD);
        o.setSellAmount(1000);
        o.setBuyResource(Resource.CLAY);
        o.setBuyAmount(1000);
        o.setRemaining(1);
        o.setCreatedAt(Instant.now());
        o.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        offers.save(o);

        abandonment.sweep();

        assertEquals(OwnerType.BARBARIAN, villages.findById(v.getId()).orElseThrow().getOwnerType());
        assertTrue(offers.findByVillageId(v.getId()).isEmpty());
    }
}
