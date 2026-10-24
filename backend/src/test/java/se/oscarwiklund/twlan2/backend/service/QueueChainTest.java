package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// After downtime the next queued order starts when the previous one finished, not when the server came back.
@SpringBootTest
@Transactional
class QueueChainTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildQueueItemRepository buildQueue;
    @Autowired TrainQueueItemRepository trainQueue;
    @Autowired TickService tick;

    @Test
    void nextOrdersStartWhenThePreviousOneFinished() {
        World world = worlds.createWorld("queue-chain-test", 1);
        Account a = new Account();
        a.setUsername("queuechainer");
        a.setPasswordHash("x");
        Village v = worlds.joinWorld(world, accounts.save(a));
        Instant hourAgo = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS); // the server was down since

        BuildQueueItem first = build(v, BuildingType.TIMBER_CAMP, 0, hourAgo.minusSeconds(60), hourAgo);
        BuildQueueItem second = build(v, BuildingType.CLAY_PIT, 1, null, null);
        TrainQueueItem spears = train(v, 0, hourAgo.minusSeconds(100), 10, 10); // 10 x 10 s: done an hour ago
        TrainQueueItem more = train(v, 1, null, 10, 5);

        tick.tick();

        assertEquals(hourAgo, buildQueue.findById(second.getId()).orElseThrow().getStartedAt());
        assertEquals(hourAgo, trainQueue.findById(more.getId()).orElseThrow().getStartedAt());
    }

    private BuildQueueItem build(Village v, BuildingType type, int position, Instant started, Instant completes) {
        BuildQueueItem q = new BuildQueueItem();
        q.setVillage(v);
        q.setType(type);
        q.setTargetLevel(5);
        q.setPosition(position);
        q.setStartedAt(started);
        q.setCompletesAt(completes);
        return buildQueue.save(q);
    }

    private TrainQueueItem train(Village v, int position, Instant started, long perUnit, int count) {
        TrainQueueItem q = new TrainQueueItem();
        q.setVillage(v);
        q.setType(UnitType.SPEAR);
        q.setTotalCount(count);
        q.setPerUnitSeconds(perUnit);
        q.setPosition(position);
        q.setStartedAt(started);
        if (started != null) q.setCompletesAt(started.plusSeconds(perUnit * count));
        return trainQueue.save(q);
    }
}
