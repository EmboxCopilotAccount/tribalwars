package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.BuildQueueItemRepository;
import se.oscarwiklund.twlan2.backend.repo.BuildingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class BuildService {

    private final BuildingRepository buildingRepository;
    private final BuildQueueItemRepository buildQueueItemRepository;
    private final VillageService villageService;
    private final GameSettings settings;
    private final TickService tickService;
    private final AchievementService achievements;

    // Real seconds, not game seconds.
    public static final long FREE_FINISH_SECONDS = 180;

    public BuildService(BuildingRepository buildingRepository, BuildQueueItemRepository buildQueueItemRepository,
                         VillageService villageService, GameSettings settings, TickService tickService,
                        AchievementService achievements) {
        this.achievements = achievements;
        this.settings = settings;
        this.tickService = tickService;
        this.buildingRepository = buildingRepository;
        this.buildQueueItemRepository = buildQueueItemRepository;
        this.villageService = villageService;
    }

    public static class BuildException extends RuntimeException {
        public BuildException(String message) { super(message); }
    }

    // a refused order (BuildException) must not doom a caller's transaction, e.g. the NPC tick trying several buildings
    @Transactional(noRollbackFor = BuildException.class)
    public BuildQueueItem enqueue(Village village, BuildingType type) {
        villageService.settleResources(village);

        int currentLevel = villageService.levelOf(village, type);
        List<BuildQueueItem> queue = buildQueueItemRepository.findByVillageOrderByPositionAsc(village);
        if (queue.stream().anyMatch(q -> q.getType() == type && q.isDemolish())) {
            throw new BuildException(type.displayName() + " is being demolished");
        }
        long alreadyQueuedOfType = queue.stream().filter(q -> q.getType() == type).count();
        int targetLevel = currentLevel + (int) alreadyQueuedOfType + 1;

        if (targetLevel > type.maxLevel) {
            throw new BuildException(type.displayName() + " is already at its maximum level (" + type.maxLevel + ")");
        }

        for (var req : type.requirements().entrySet()) {
            if (villageService.levelOf(village, req.getKey()) < req.getValue()) {
                throw new BuildException(type.displayName() + " requires " + req.getKey().displayName() + " level " + req.getValue());
            }
        }

        double costMultiplier = queueCostMultiplier(village.getWorld(), queue.size());
        double wood = type.woodCost(targetLevel) * costMultiplier;
        double clay = type.clayCost(targetLevel) * costMultiplier;
        double iron = type.ironCost(targetLevel) * costMultiplier;

        int capacity = villageService.warehouseCapacity(village);
        if (wood > capacity || clay > capacity || iron > capacity) {
            throw new BuildException("Warehouse too small for " + type.displayName() + " level " + targetLevel);
        }

        int pendingPop = pendingPop(queue);
        int freePop = villageService.populationCapacity(village) - villageService.populationUsed(village) - pendingPop;
        // a level that takes no farm space (farm, warehouse) is always allowed, even with the village over its limit
        if (type.popIncrease(targetLevel) > 0 && type.popIncrease(targetLevel) > freePop) {
            throw new BuildException("Farm too small for " + type.displayName() + " level " + targetLevel
                    + " (needs " + type.popIncrease(targetLevel) + " free population, only " + Math.max(0, freePop) + " left)");
        }

        if (village.getWood() < wood || village.getClay() < clay || village.getIron() < iron) {
            throw new BuildException("Not enough resources for " + type.displayName() + " level " + targetLevel);
        }

        village.setWood(village.getWood() - wood);
        village.setClay(village.getClay() - clay);
        village.setIron(village.getIron() - iron);

        BuildQueueItem item = new BuildQueueItem();
        item.setVillage(village);
        item.setType(type);
        item.setTargetLevel(targetLevel);
        item.setPosition(queue.size());
        if (costMultiplier != 1.0) {
            item.setCostMultiplier(costMultiplier);
        }

        if (queue.isEmpty()) {
            int hqLevel = villageService.levelOf(village, BuildingType.HEADQUARTERS);
            Instant now = Instant.now();
            item.setStartedAt(now);
            item.setCompletesAt(now.plusSeconds(
                    type.buildTimeSeconds(targetLevel, hqLevel, settings.speedOf(village), WorldSettings.number(village.getWorld(), "buildMainFactor"))));
        }

        return buildQueueItemRepository.save(item);
    }

    // Farm space the queued upgrades will take (a demolition frees its space only once it is done).
    static int pendingPop(List<BuildQueueItem> queue) {
        return queue.stream().filter(q -> !q.isDemolish()).mapToInt(q -> q.getType().popIncrease(q.getTargetLevel())).sum();
    }

    // The HQ level from which buildings can be demolished (the original's headquarters description).
    public static final int DEMOLISH_HQ_LEVEL = 15;

    // Below this a building can't be demolished: a village keeps its headquarters, farm and warehouse.
    public static int minLevel(BuildingType type) {
        return type == BuildingType.HEADQUARTERS || type == BuildingType.FARM || type == BuildingType.WAREHOUSE ? 1 : 0;
    }

    // Queues taking one level of `type` down: free, nothing comes back, and it takes the build time of the level
    // removed (the original's demolition time is compiled and not readable; this is the natural symmetric default).
    // A building has either upgrades or demolitions queued, never both, so its levels stay one step apart.
    @Transactional(noRollbackFor = BuildException.class)
    public BuildQueueItem demolish(Village village, BuildingType type) {
        villageService.settleResources(village);
        if (villageService.levelOf(village, BuildingType.HEADQUARTERS) < DEMOLISH_HQ_LEVEL) {
            throw new BuildException("Buildings can be demolished from Headquarters level " + DEMOLISH_HQ_LEVEL);
        }
        List<BuildQueueItem> queue = buildQueueItemRepository.findByVillageOrderByPositionAsc(village);
        if (queue.stream().anyMatch(q -> q.getType() == type && !q.isDemolish())) {
            throw new BuildException(type.displayName() + " is being upgraded");
        }
        long queuedDemolitions = queue.stream().filter(q -> q.getType() == type && q.isDemolish()).count();
        int targetLevel = villageService.levelOf(village, type) - (int) queuedDemolitions - 1;
        if (targetLevel < minLevel(type)) {
            throw new BuildException(type.displayName() + " can't be demolished any further");
        }
        if (type == BuildingType.FARM) {
            int capacity = (int) Math.floor(BuildingType.farmCapacity(targetLevel) * BonusType.populationFactor(village));
            int needed = villageService.populationUsed(village) + pendingPop(queue);
            if (capacity < needed) {
                throw new BuildException("The farm can't be demolished: level " + targetLevel + " only feeds " + capacity
                        + " and the village needs " + needed);
            }
        }

        BuildQueueItem item = new BuildQueueItem();
        item.setVillage(village);
        item.setType(type);
        item.setTargetLevel(targetLevel);
        item.setDemolish(true);
        item.setPosition(queue.size());
        if (queue.isEmpty()) {
            int hqLevel = villageService.levelOf(village, BuildingType.HEADQUARTERS);
            Instant now = Instant.now();
            item.setStartedAt(now);
            item.setCompletesAt(now.plusSeconds(type.buildTimeSeconds(item.timedLevel(), hqLevel, settings.speedOf(village),
                    WorldSettings.number(village.getWorld(), "buildMainFactor"))));
        }
        return buildQueueItemRepository.save(item);
    }

    // World settings "buildqueueStart"/"buildqueueMultiply": the price factor of an order at this queue position.
    public static double queueCostMultiplier(World world, int position) {
        int freeSlots = (int) WorldSettings.number(world, "buildqueueStart");
        double multiply = WorldSettings.number(world, "buildqueueMultiply");
        int extra = position - freeSlots + 1;
        return extra <= 0 ? 1.0 : Math.pow(multiply, extra);
    }

    @Transactional
    public void finishFree(Village village, Long itemId) {
        BuildQueueItem item = buildQueueItemRepository.findByVillageOrderByPositionAsc(village).stream()
                .filter(q -> q.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new BuildException("No such construction order"));
        Instant now = Instant.now();
        if (item.getCompletesAt() == null) {
            throw new BuildException("Only the order that is currently being built can be completed");
        }
        long left = item.getCompletesAt().getEpochSecond() - now.getEpochSecond();
        if (left > FREE_FINISH_SECONDS) {
            throw new BuildException("Orders can only be completed for free when less than 3 minutes remain");
        }
        item.setCompletesAt(now);
        buildQueueItemRepository.save(item);
        // Completes just this item, not tickService.processBuildQueues(now) - that would process every other
        // due item server-wide too, widening the window to collide with the real scheduled tick (it used to
        // occasionally deadlock H2 on the `building` table when both touched an unrelated village at once).
        tickService.completeBuildItem(item, now);
        achievements.count(village.getOwner(), village.getWorld(), "instant", 1);
    }

    // Also cancels every later order for the same building (they depend on it), refunding their full cost
    // (capped by warehouse capacity). Then re-numbers the queue and starts the next order if the active one was removed.
    @Transactional
    public void cancel(Village village, Long itemId) {
        villageService.settleResources(village);
        List<BuildQueueItem> queue = buildQueueItemRepository.findByVillageOrderByPositionAsc(village);
        BuildQueueItem target = queue.stream().filter(q -> q.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new BuildException("No such construction order"));

        int capacity = villageService.warehouseCapacity(village);
        // a demolition takes the later demolitions of that building with it (they go further down)
        List<BuildQueueItem> removed = queue.stream()
                .filter(q -> q.getType() == target.getType() && q.isDemolish() == target.isDemolish()
                        && (target.isDemolish() ? q.getTargetLevel() <= target.getTargetLevel() : q.getTargetLevel() >= target.getTargetLevel()))
                .toList();
        for (BuildQueueItem q : removed) {
            if (q.isDemolish()) continue; // a demolition cost nothing
            double mult = q.getCostMultiplier() != null ? q.getCostMultiplier() : 1.0;
            village.setWood(Math.max(village.getWood(), Math.min(capacity, village.getWood() + q.getType().woodCost(q.getTargetLevel()) * mult)));
            village.setClay(Math.max(village.getClay(), Math.min(capacity, village.getClay() + q.getType().clayCost(q.getTargetLevel()) * mult)));
            village.setIron(Math.max(village.getIron(), Math.min(capacity, village.getIron() + q.getType().ironCost(q.getTargetLevel()) * mult)));
        }
        for (BuildQueueItem q : removed) buildQueueItemRepository.deleteByIdSafe(q.getId());

        List<BuildQueueItem> rest = queue.stream().filter(q -> !removed.contains(q)).toList();
        Instant now = Instant.now();
        for (int i = 0; i < rest.size(); i++) {
            BuildQueueItem q = rest.get(i);
            q.setPosition(i);
            if (i == 0 && q.getStartedAt() == null) {
                int hqLevel = villageService.levelOf(village, BuildingType.HEADQUARTERS);
                q.setStartedAt(now);
                q.setCompletesAt(now.plusSeconds(q.getType().buildTimeSeconds(
                        q.timedLevel(), hqLevel, settings.speedOf(village), WorldSettings.number(village.getWorld(), "buildMainFactor"))));
            }
            buildQueueItemRepository.save(q);
        }
    }

    // Moves a waiting order to another place in the queue (the drag handle of the build queue); the order being built
    // stays first. Orders for the same building keep building up one level at a time: their levels are renumbered in
    // the new order, each level keeping the surcharge it was paid with, so cancel() still refunds what was paid.
    @Transactional
    public void move(Village village, Long itemId, int toPosition) {
        List<BuildQueueItem> queue = new ArrayList<>(buildQueueItemRepository.findByVillageOrderByPositionAsc(village));
        BuildQueueItem item = queue.stream().filter(q -> q.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new BuildException("No such construction order"));
        int first = !queue.isEmpty() && queue.get(0).getStartedAt() != null ? 1 : 0;
        if (queue.indexOf(item) < first) {
            throw new BuildException("The order that is being built can't be moved");
        }
        queue.remove(item);
        queue.add(Math.max(first, Math.min(queue.size(), toPosition)), item);

        List<BuildQueueItem> sameType = queue.stream().filter(q -> q.getType() == item.getType()).toList();
        // upgrades go up one level at a time, demolitions down
        Comparator<BuildQueueItem> step = Comparator.comparingInt(BuildQueueItem::getTargetLevel);
        List<BuildQueueItem> byLevel = sameType.stream().sorted(item.isDemolish() ? step.reversed() : step).toList();
        List<Integer> levels = byLevel.stream().map(BuildQueueItem::getTargetLevel).toList();
        List<Double> paid = byLevel.stream().map(BuildQueueItem::getCostMultiplier).toList();
        for (int i = 0; i < sameType.size(); i++) {
            sameType.get(i).setTargetLevel(levels.get(i));
            sameType.get(i).setCostMultiplier(paid.get(i));
        }
        for (int i = 0; i < queue.size(); i++) {
            queue.get(i).setPosition(i);
            buildQueueItemRepository.save(queue.get(i));
        }
    }
}
