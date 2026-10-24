package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Players on equal points share a rank (1, 1, 3), as on the profile, stats and tribe pages.
@SpringBootTest
@Transactional
class RankingTiesTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired RankingService ranking;

    private Village join(World world, String name) {
        Account a = new Account();
        a.setUsername(name);
        a.setPasswordHash("x");
        return worlds.joinWorld(world, accounts.save(a));
    }

    private static int rankOf(List<RankingService.PlayerRow> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().rank();
    }

    @Test
    void equalPointsShareARank() {
        World world = worlds.createWorld("ranking-ties-test", 1);
        Village top = join(world, "rank-top");
        join(world, "rank-tie-a");
        join(world, "rank-tie-b");
        Building hq = buildings.findByVillageAndType(top, BuildingType.HEADQUARTERS).orElseThrow();
        hq.setLevel(hq.getLevel() + 5);
        buildings.save(hq);

        List<RankingService.PlayerRow> rows = ranking.players(world, null, false).rows();
        int tieA = rankOf(rows, "rank-tie-a"), tieB = rankOf(rows, "rank-tie-b");
        assertEquals(tieA, tieB);
        assertTrue(rankOf(rows, "rank-top") < tieA);
    }
}
