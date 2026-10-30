package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.live.LiveUpdates;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import se.oscarwiklund.twlan2.backend.service.npc.NpcVillageNames;

import java.util.List;
import java.util.Map;

// Triggers when a village's loyalty is driven to zero.
@Service
public class ConquestService {

    public static final double LOYALTY_AFTER_CONQUEST = 25;

    private final LiveUpdates live;
    private final VillageRepository villages;
    private final BuildQueueItemRepository buildQueue;
    private final TrainQueueItemRepository trainQueue;
    private final ResearchQueueItemRepository researchQueue;
    private final UnitStockRepository unitStock;
    private final MovementRepository movements;
    private final VillageService villageService;
    private final BuildingRepository buildings;
    private final NobleService nobles;
    private final SupportService support;
    private final GroupService groups;
    private final MarketService market;
    private final se.oscarwiklund.twlan2.backend.service.npc.NpcIntelService npcIntel;

    public ConquestService(LiveUpdates live, VillageRepository villages, BuildQueueItemRepository buildQueue, TrainQueueItemRepository trainQueue,
                           UnitStockRepository unitStock, MovementRepository movements, VillageService villageService, NobleService nobles,
                           BuildingRepository buildings, SupportService support, GroupService groups, MarketService market,
                           se.oscarwiklund.twlan2.backend.service.npc.NpcIntelService npcIntel,
                           ResearchQueueItemRepository researchQueue) {
        this.live = live;
        this.researchQueue = researchQueue;
        this.market = market;
        this.support = support;
        this.groups = groups;
        this.buildings = buildings;
        this.villages = villages;
        this.buildQueue = buildQueue;
        this.trainQueue = trainQueue;
        this.unitStock = unitStock;
        this.movements = movements;
        this.villageService = villageService;
        this.npcIntel = npcIntel;
        this.nobles = nobles;
    }

    // Given to a village with no buildings, so every conquered village is at least as developed as a freshly founded one.
    private static final Map<BuildingType, Integer> STARTER_BASE = Map.of(
            BuildingType.HEADQUARTERS, 1, BuildingType.TIMBER_CAMP, 2, BuildingType.CLAY_PIT, 2, BuildingType.IRON_MINE, 2,
            BuildingType.FARM, 1, BuildingType.WAREHOUSE, 1);

    // Buildings and stored resources stay, but the previous owner's orders, garrison and troops still out from it
    // are gone. It counts against the conqueror's noblemen limit.
    @Transactional
    public void conquer(Village village, Account conqueror) {
        if (conqueror != null && village.getOwner() != null && conqueror.getId().equals(village.getOwner().getId())) {
            conquerOwn(village, conqueror);
            return;
        }
        villageService.settleResources(village);
        groups.dropVillage(village.getId()); // the old owner's groups lose it
        market.dropVillage(village.getId()); // its offers and merchants on the road are gone
        npcIntel.dropVillage(village.getId()); // what NPCs knew about it no longer holds
        support.villageConquered(village); // guests go home, the old owner's stationed armies are lost with it
        buildQueue.deleteAll(buildQueue.findByVillageOrderByPositionAsc(village));
        trainQueue.deleteAll(trainQueue.findByVillageOrderByPositionAsc(village));
        researchQueue.deleteAll(researchQueue.findByVillageOrderByPositionAsc(village)); // (what is researched stays)
        unitStock.deleteAll(unitStock.findByVillage(village));
        List<Movement> own = movements.findByOriginVillageOrTargetVillage(village, village);
        for (Movement m : own) {
            boolean fromHere = m.getOriginVillage().getId().equals(village.getId());
            boolean toHere = m.getTargetVillage().getId().equals(village.getId());
            // troops of the old owner that were away: commands still on the road from here, and troops on their way
            // back home. Return trips that start here belong to others (an earlier wave, guests sent home): they go on.
            if (fromHere && m.getType() != MovementType.RETURN || toHere && m.getType() == MovementType.RETURN) movements.delete(m);
        }
        STARTER_BASE.forEach((type, level) -> {
            Building b = buildings.findByVillageAndType(village, type).orElseGet(() -> {
                Building n = new Building();
                n.setVillage(village);
                n.setType(type);
                n.setLevel(0);
                return n;
            });
            if (b.getLevel() < level) {
                b.setLevel(level);
                buildings.save(b);
            }
        });
        Account previous = village.getOwner();
        if (conqueror != null && conqueror.isNpc() && !NpcVillageNames.isOwnName(conqueror, village.getName())) { // players name their own (#128)
            List<String> inUse = villages.findByOwner(conqueror).stream().map(Village::getName).toList();
            village.setName(NpcVillageNames.nextFor(conqueror, inUse));
        }
        village.setOwner(conqueror);
        village.setOwnerType(OwnerType.PLAYER);
        villageService.setLoyalty(village, LOYALTY_AFTER_CONQUEST);
        villages.save(village);
        if (village.getWorld() != null) {
            live.toWorld(village.getWorld().getId(), LiveUpdates.MAP);
            live.toAccount(previous, village.getWorld().getId(), LiveUpdates.VILLAGE);
            live.toAccount(conqueror, village.getWorld().getId(), LiveUpdates.VILLAGE);
        }
        nobles.addConquest(conqueror, village.getWorld());
    }

    // Self-conquest (#125): the nobleman is used up and loyalty starts over, but nothing changes hands, so none of the
    // handover above applies - it would wipe the owner's own orders and the armies the village has out.
    private void conquerOwn(Village village, Account owner) {
        villageService.setLoyalty(village, LOYALTY_AFTER_CONQUEST);
        villages.save(village);
        if (village.getWorld() != null) {
            live.toWorld(village.getWorld().getId(), LiveUpdates.MAP);
            live.toAccount(owner, village.getWorld().getId(), LiveUpdates.VILLAGE);
        }
        nobles.addConquest(owner, village.getWorld());
    }
}
