package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import se.oscarwiklund.twlan2.backend.domain.MarketOffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRulesTest {

    @Test
    void merchantsGrowWithTheMarketLevel() {
        assertEquals(0, MarketService.merchantsForLevel(0));
        assertEquals(1, MarketService.merchantsForLevel(1));
        assertEquals(10, MarketService.merchantsForLevel(10));
        assertEquals(14, MarketService.merchantsForLevel(12));
        assertEquals(235, MarketService.merchantsForLevel(25));
    }

    @Test
    void levelsOutsideTheRangeAreClamped() {
        assertEquals(0, MarketService.merchantsForLevel(-3));
        assertEquals(235, MarketService.merchantsForLevel(99));
    }

    @Test
    void everyStartedThousandNeedsAMerchant() {
        assertEquals(1, MarketService.merchantsNeeded(1));
        assertEquals(1, MarketService.merchantsNeeded(1000));
        assertEquals(2, MarketService.merchantsNeeded(1001));
        assertEquals(5, MarketService.merchantsNeeded(4500));
    }

    private static MarketOffer offer(int sell, int buy) {
        MarketOffer o = new MarketOffer();
        o.setSellAmount(sell);
        o.setBuyAmount(buy);
        return o;
    }

    @Test
    void npcsOnlyTakeUpOffersThatAreFairToThem() {
        assertFalse(NpcMarketService.fairForNpc(offer(1000, 10000))); // the player wants ten times what they give
        assertFalse(NpcMarketService.fairForNpc(offer(800, 1000)));
        assertTrue(NpcMarketService.fairForNpc(offer(850, 1000)));
        assertTrue(NpcMarketService.fairForNpc(offer(1000, 1000)));
        assertTrue(NpcMarketService.fairForNpc(offer(2000, 1000))); // a generous offer
    }
}
