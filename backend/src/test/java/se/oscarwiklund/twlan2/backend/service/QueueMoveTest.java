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

import static org.junit.jupiter.api.Assertions.*;

// Waiting build and recruit orders can be dragged to another place in their queue; the running order stays first.
@SpringBootTest
@Transactional
class QueueMoveTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildQueueItemRepository buildQueue;
    @Autowired TrainQueueItemRepository trainQueue;
    @Autowired BuildService buildService;
    @Autowired TrainService trainService;

    Village v;

    @BeforeEach
    void setUp() {
        World world = worlds.createWorld("queue-move-test", 1);
        Account a = new Account();
        a.setUsername("queuemover-" + System.nanoTime());
        a.setPasswordHash("x");
        v = worlds.joinWorld(world, accounts.save(a));
    }

    @Test
    void buildOrdersMoveAndKeepTheirLevelsInOrder() {
        BuildQueueItem running = build(BuildingType.TIMBER_CAMP, 5, 0, null, Instant.now());
        BuildQueueItem wall1 = build(BuildingType.WALL, 1, 1, null, null);
        BuildQueueItem pit = build(BuildingType.CLAY_PIT, 3, 2, null, null);
        BuildQueueItem wall2 = build(BuildingType.WALL, 2, 3, 1.2, null);

        buildService.move(v, wall2.getId(), 1);

        List<BuildQueueItem> q = buildQueue.findByVillageOrderByPositionAsc(v);
        assertEquals(List.of(running.getId(), wall2.getId(), wall1.getId(), pit.getId()), q.stream().map(BuildQueueItem::getId).toList());
        assertEquals(1, q.get(1).getTargetLevel()); // the moved wall order now builds level 1 ...
        assertNull(q.get(1).getCostMultiplier());
        assertEquals(2, q.get(2).getTargetLevel()); // ... and level 2 keeps the surcharge it was paid with
        assertEquals(1.2, q.get(2).getCostMultiplier());

        assertThrows(BuildService.BuildException.class, () -> buildService.move(v, running.getId(), 2));
        buildService.move(v, pit.getId(), 0); // can't go before the running order
        assertEquals(running.getId(), buildQueue.findByVillageOrderByPositionAsc(v).get(0).getId());
    }

    @Test
    void recruitOrdersMoveWithinTheirBuilding() {
        TrainQueueItem spears = train(UnitType.SPEAR, 0, Instant.now());
        TrainQueueItem axes = train(UnitType.AXE, 1, null);
        TrainQueueItem light = train(UnitType.LIGHT, 0, Instant.now());
        TrainQueueItem swords = train(UnitType.SWORD, 2, null);

        trainService.move(v, swords.getId(), 1);

        assertEquals(List.of(spears.getId(), swords.getId(), axes.getId()),
                trainQueue.findByVillageOrderByPositionAsc(v).stream()
                        .filter(t -> t.getType().recruitBuilding == BuildingType.BARRACKS).map(TrainQueueItem::getId).toList());
        assertEquals(0, trainQueue.findById(light.getId()).orElseThrow().getPosition());
        assertThrows(TrainService.TrainException.class, () -> trainService.move(v, spears.getId(), 2));
    }

    private BuildQueueItem build(BuildingType type, int level, int position, Double mult, Instant started) {
        BuildQueueItem q = new BuildQueueItem();
        q.setVillage(v);
        q.setType(type);
        q.setTargetLevel(level);
        q.setPosition(position);
        q.setCostMultiplier(mult);
        q.setStartedAt(started);
        if (started != null) q.setCompletesAt(started.plusSeconds(3600));
        return buildQueue.save(q);
    }

    private TrainQueueItem train(UnitType type, int position, Instant started) {
        TrainQueueItem q = new TrainQueueItem();
        q.setVillage(v);
        q.setType(type);
        q.setTotalCount(10);
        q.setPerUnitSeconds(60);
        q.setPosition(position);
        q.setStartedAt(started);
        if (started != null) q.setCompletesAt(started.plusSeconds(600));
        return trainQueue.save(q);
    }
}
