package se.oscarwiklund.twlan2.backend.web;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.BuildService;
import se.oscarwiklund.twlan2.backend.service.GameSettings;
import se.oscarwiklund.twlan2.backend.service.MailService;
import se.oscarwiklund.twlan2.backend.service.MarketService;
import se.oscarwiklund.twlan2.backend.service.MovementService;
import se.oscarwiklund.twlan2.backend.service.NobleService;
import se.oscarwiklund.twlan2.backend.service.ReportService;
import se.oscarwiklund.twlan2.backend.service.ResearchService;
import se.oscarwiklund.twlan2.backend.service.ForumService;
import se.oscarwiklund.twlan2.backend.service.SupportService;
import se.oscarwiklund.twlan2.backend.service.TrainService;
import se.oscarwiklund.twlan2.backend.service.WorldSettings;
import se.oscarwiklund.twlan2.backend.service.VillageService;
import se.oscarwiklund.twlan2.backend.service.VictoryService;
import se.oscarwiklund.twlan2.backend.web.dto.OverviewDto;
import se.oscarwiklund.twlan2.backend.web.dto.ReportDto;
import se.oscarwiklund.twlan2.backend.web.dto.VillageStateDto;
import se.oscarwiklund.twlan2.backend.web.dto.VillageStateDto.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GameFacade {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager; // to mark display-only copies read-only (ownVillages)

    private final VillageRepository villageRepository;
    private final BuildingRepository buildingRepository;
    private final BuildQueueItemRepository buildQueueItemRepository;
    private final UnitStockRepository unitStockRepository;
    private final TrainQueueItemRepository trainQueueItemRepository;
    private final MovementRepository movementRepository;
    private final CombatReportRepository combatReportRepository;
    private final VillageService villageService;
    private final GameSettings settings;
    private final WorldRepository worldRepository;
    private final TrainService trainService;
    private final NobleService nobleService;
    private final ResearchService researchService;
    private final PaladinProfileRepository paladinProfileRepository;
    private final SupportService supportService;
    private final ForumService forumService;
    private final MailService mailService;
    private final MarketService marketService;
    private final VictoryService victoryService;

    public GameFacade(VillageRepository villageRepository, BuildingRepository buildingRepository,
                       BuildQueueItemRepository buildQueueItemRepository, UnitStockRepository unitStockRepository,
                       TrainQueueItemRepository trainQueueItemRepository, MovementRepository movementRepository,
                       CombatReportRepository combatReportRepository, VillageService villageService,
                       GameSettings settings, WorldRepository worldRepository, TrainService trainService,
                       PaladinProfileRepository paladinProfileRepository, NobleService nobleService,
                       ResearchService researchService, SupportService supportService, ForumService forumService, MarketService marketService, MailService mailService, VictoryService victoryService) {
        this.victoryService = victoryService;
        this.marketService = marketService;
        this.forumService = forumService;
        this.mailService = mailService;
        this.supportService = supportService;
        this.researchService = researchService;
        this.nobleService = nobleService;
        this.paladinProfileRepository = paladinProfileRepository;
        this.trainService = trainService;
        this.settings = settings;
        this.worldRepository = worldRepository;
        this.villageRepository = villageRepository;
        this.buildingRepository = buildingRepository;
        this.buildQueueItemRepository = buildQueueItemRepository;
        this.unitStockRepository = unitStockRepository;
        this.trainQueueItemRepository = trainQueueItemRepository;
        this.movementRepository = movementRepository;
        this.combatReportRepository = combatReportRepository;
        this.villageService = villageService;
    }

    // X-World-Id header, else the first world.
    public World currentWorld() {
        Long id = WorldContext.get();
        if (id != null) {
            return worldRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown world " + id));
        }
        return worldRepository.findAllByOrderByIdAsc().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No world exists"));
    }

    public static class NotJoinedException extends RuntimeException {
        public NotJoinedException(String message) { super(message); }
    }

    // Honours X-Village-Id (via VillageContext) when set, else falls back to the lowest-id village;
    // the account must have joined the world first.
    public Village playerHomeVillage() {
        World world = currentWorld();
        Account account = AccountContext.get();
        if (account == null) {
            throw new AuthService.AuthException("Please log in.");
        }
        List<Village> owned = villageRepository.findByWorldAndOwner(world, account);
        Long wanted = VillageContext.get();
        if (wanted != null) {
            for (Village v : owned) if (v.getId().equals(wanted)) return v;
        }
        return owned.stream().min(java.util.Comparator.comparing(Village::getId))
                .orElseThrow(() -> new NotJoinedException("You have not joined " + world.getName() + " yet."));
    }

    public Village ownVillage(Long id) {
        Account account = AccountContext.get();
        if (account == null) throw new AuthService.AuthException("Please log in.");
        return villageRepository.findByWorldAndOwner(currentWorld(), account).stream()
                .filter(v -> v.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That is not your village."));
    }

    // rounded up, so the screens never offer an order the server would refuse for a fraction of a resource
    private static int withSurcharge(int cost, double factor) {
        return (int) Math.ceil(cost * factor - 1e-9);
    }

    // must run in a transaction: open-in-view is off, and a movement's units load lazily.
    // Saves THIS village: the order endpoints hand in the (detached) village their service just charged, and this save is
    // what persists that (BuildService.enqueue etc. do not save it themselves). The player's other villages are only
    // settled for display and never saved (ownVillages): saving them too locked several rows in an order that differed
    // between tabs, and H2 rolled one request back as a deadlock victim (#89).
    @Transactional
    public VillageStateDto toDto(Village village) {
        villageService.settleResources(village);
        villageRepository.save(village);

        int hqLevel = villageService.levelOf(village, BuildingType.HEADQUARTERS);
        double hqTimeFactor = WorldSettings.number(village.getWorld(), "buildMainFactor");
        List<BuildQueueItem> queue = buildQueueItemRepository.findByVillageOrderByPositionAsc(village);

        // the next order lands at the end of the queue and pays that position's surcharge (BuildService.enqueue)
        double queueFactor = BuildService.queueCostMultiplier(village.getWorld(), queue.size());
        List<BuildingDto> buildings = Arrays.stream(BuildingType.values()).map(type -> {
            int level = villageService.levelOf(village, type);
            long queuedOfType = queue.stream().filter(q -> q.getType() == type && !q.isDemolish()).count();
            int targetLevel = level + (int) queuedOfType + 1;
            boolean maxed = targetLevel > type.maxLevel;
            // the next demolition takes level `down` away (BuildService.demolish refuses the same cases)
            long demolitions = queue.stream().filter(q -> q.getType() == type && q.isDemolish()).count();
            boolean upgrading = queue.stream().anyMatch(q -> q.getType() == type && !q.isDemolish());
            int down = level - (int) demolitions;
            long demolishSeconds = hqLevel >= BuildService.DEMOLISH_HQ_LEVEL && !upgrading && down - 1 >= BuildService.minLevel(type)
                    ? type.buildTimeSeconds(down, hqLevel, settings.speedOf(village), hqTimeFactor) : 0;
            return new BuildingDto(type.name(), level, type.maxLevel, maxed,
                    maxed ? 0 : withSurcharge(type.woodCost(targetLevel), queueFactor),
                    maxed ? 0 : withSurcharge(type.clayCost(targetLevel), queueFactor),
                    maxed ? 0 : withSurcharge(type.ironCost(targetLevel), queueFactor),
                    maxed ? 0 : type.buildTimeSeconds(targetLevel, hqLevel, settings.speedOf(village), hqTimeFactor),
                    maxed ? 0 : type.popIncrease(targetLevel),
                    type.requirements().entrySet().stream()
                            .allMatch(r -> villageService.levelOf(village, r.getKey()) >= r.getValue()),
                    demolitions > 0, demolishSeconds);
        }).toList();

        List<BuildQueueDto> buildQueueDtos = queue.stream()
                .map(q -> new BuildQueueDto(q.getType().name(), q.getTargetLevel(), q.getStartedAt(), q.getCompletesAt(), q.getId(),
                        q.getType().buildTimeSeconds(q.timedLevel(), hqLevel, settings.speedOf(village), hqTimeFactor),
                        q.isDemolish() ? 0 : q.getType().popIncrease(q.getTargetLevel()), q.isDemolish()))
                .toList();

        Map<String, Integer> units = new LinkedHashMap<>();
        for (UnitStock stock : unitStockRepository.findByVillage(village)) {
            units.put(stock.getType().name(), stock.getCount());
        }

        List<TrainQueueDto> trainQueueDtos = trainQueueItemRepository.findByVillageOrderByPositionAsc(village).stream()
                .map(t -> new TrainQueueDto(t.getType().name(), t.getTotalCount(), t.getProducedCount(), t.getStartedAt(), t.getCompletesAt(), t.getId(), t.getPerUnitSeconds(), t.isDecommission()))
                .toList();

        List<UnitCostDto> unitCosts = Arrays.stream(UnitType.values())
                .map(t -> new UnitCostDto(t.name(), t.woodCost, t.clayCost, t.ironCost, t.popCost, t.attack, t.defense,
                        t.speedMinutesPerField, t.carryCapacity, t.defenseCavalry, t.defenseArcher))
                .toList();

        List<MovementDto> outgoing = movementRepository.findByOriginVillageOrTargetVillage(village, village).stream()
                .filter(m -> m.getType() != MovementType.RETURN && m.getOriginVillage().getId().equals(village.getId()))
                .map(this::toOutgoingDto)
                .toList();

        List<MovementDto> incoming = incomingDtos(village, movementRepository.findByOriginVillageOrTargetVillage(village, village));

        TrainService.PaladinStatus paladin = trainService.paladinStatus(village);
        return new VillageStateDto(
                village.getId(), village.getName(), village.getX(), village.getY(),
                village.getWood(), village.getClay(), village.getIron(),
                villageService.warehouseCapacity(village),
                villageService.populationUsed(village), villageService.populationCapacity(village),
                perHour(village, BuildingType.TIMBER_CAMP), perHour(village, BuildingType.CLAY_PIT), perHour(village, BuildingType.IRON_MINE),
                buildings, buildQueueDtos, units, trainQueueDtos, unitCosts, outgoing, incoming,
                settings.speedOf(village),
                settings.travelSpeedOf(village),
                WorldSettings.number(village.getWorld(), "basicDefense"),
                WorldSettings.number(village.getWorld(), "nightBonus"),
                WorldSettings.bool(village.getWorld(), "destroyBuildings"),
                WorldSettings.bool(village.getWorld(), "attackSelf"),
                "tribe".equals(WorldSettings.get(village.getWorld(), "supportTargets")),
                !"off".equals(WorldSettings.get(village.getWorld(), "morale")),
                (int) Math.round((queueFactor - 1) * 100),
                WorldSettings.number(village.getWorld(), "prodToSeconds"), WorldSettings.number(village.getWorld(), "prodToMinutes"),
                settings.worldNameOf(village),
                village.getWorld() == null ? null : village.getWorld().getId(),
                village.getOwner() == null ? "Player" : village.getOwner().getUsername(),
                village.getOwner() == null ? PaladinProfile.DEFAULT_NAME
                        : paladinProfileRepository.findById(village.getOwner().getId()).map(PaladinProfile::getName).orElse(PaladinProfile.DEFAULT_NAME),
                paladin == null ? null : paladin.state(),
                paladin == null ? null : paladin.at().getName(),
                WorldSettings.isNight(village.getWorld(), java.time.LocalTime.now()),
                (int) Math.ceil(villageService.loyaltyOf(village)),
                village.getOwner() == null ? null : toNobleDto(nobleService.info(village.getOwner(), village.getWorld())),
                ownVillages(village),
                researchList(village),
                researchService.queueOf(village).stream()
                        .map(q -> new ResearchQueueDto(q.getId(), q.getUnit().name(), q.getDurationSeconds(), q.getStartedAt(), q.getCompletesAt()))
                        .toList(),
                supportService.guestsAt(village).stream().map(g -> army(g, g.getOriginVillage())).toList(),
                supportService.stationedFrom(village).stream().map(g -> army(g, g.getHostVillage())).toList(),
                village.getOwner() != null && village.getWorld() != null && forumService.hasUnread(village.getOwner(), village.getWorld()),
                new MerchantsDto(marketService.totalMerchants(village), marketService.availableMerchants(village), MarketService.CAPACITY),
                (int) mailService.unreadCount(village.getOwner(), village.getWorld()),
                bonusCode(village),
                worldVictoryDto(village.getWorld())
        );
    }

    private WorldVictoryDto worldVictoryDto(World world) {
        if (world == null) return null;
        VictoryService.Outcome outcome = victoryService.outcomeOf(world);
        return outcome == null ? null
                : new WorldVictoryDto(outcome.type().name(), outcome.wonTribeId(), outcome.wonTribeName(), outcome.wonAt());
    }

    static Integer bonusCode(Village village) {
        BonusType b = BonusType.of(village);
        return b == null ? null : b.code;
    }

    private ArmyDto army(StationedTroops troops, Village other) {
        return new ArmyDto(troops.getId(), other.getId(), other.getName(), other.getX(), other.getY(),
                other.getOwner() == null ? null : other.getOwner().getUsername(), stringifyUnits(troops.getUnits()));
    }

    private List<ResearchDto> researchList(Village village) {
        return ResearchService.TECHS.values().stream()
                .map(t -> new ResearchDto(t.unit().name(), researchService.isResearched(village, t.unit()),
                        t.unit().requirementsMet(b -> villageService.levelOf(village, b)),
                        t.wood(), t.clay(), t.iron(), researchService.secondsFor(village, t.unit())))
                .toList();
    }

    private NobleDto toNobleDto(NobleService.Info i) {
        return new NobleDto(i.coins(), i.limit(), i.existing(), i.inProduction(), i.conquered(), i.possible(),
                i.coinsMissing(), i.coinsAlready(), i.costWood(), i.costClay(), i.costIron());
    }

    // Feeds the village switcher and the combined overview.
    private List<OwnVillageDto> ownVillages(Village current) {
        if (current.getOwner() == null || current.getWorld() == null) return List.of();
        List<OwnVillageDto> out = new java.util.ArrayList<>();
        for (Village v : villageRepository.findByWorldAndOwner(current.getWorld(), current.getOwner())) {
            int points = 0;
            for (Building b : buildingRepository.findByVillage(v)) points += b.getType().points(b.getLevel());
            // display only: never written back (see toDto), so no row lock on the other villages
            if (!v.getId().equals(current.getId())) entityManager.unwrap(org.hibernate.Session.class).setReadOnly(v, true);
            villageService.settleResources(v);
            Map<String, Integer> home = new LinkedHashMap<>();
            for (UnitStock stock : unitStockRepository.findByVillage(v)) home.put(stock.getType().name(), stock.getCount());
            out.add(new OwnVillageDto(v.getId(), v.getName(), v.getX(), v.getY(), points, v.getWood(), v.getClay(), v.getIron(),
                    villageService.warehouseCapacity(v), villageService.populationUsed(v), villageService.populationCapacity(v),
                    (int) Math.ceil(villageService.loyaltyOf(v)), villageService.levelOf(v, BuildingType.FARM), home, bonusCode(v)));
        }
        out.sort(java.util.Comparator.comparing(OwnVillageDto::name).thenComparing(OwnVillageDto::id));
        return out;
    }

    // returning troops, support and (other players') attacks heading for the village, out of its movements
    private List<MovementDto> incomingDtos(Village village, List<Movement> movements) {
        return movements.stream()
                .filter(m -> m.getTargetVillage().getId().equals(village.getId())
                        && (m.getType() == MovementType.RETURN || m.getType() == MovementType.SUPPORT
                        || (m.getType() == MovementType.ATTACK && !MovementService.isSameOwner(m.getOriginVillage(), village))))
                .map(m -> m.getType() == MovementType.ATTACK
                        // what an enemy sends is not shown, only that it is coming
                        ? new MovementDto(m.getId(), "INCOMING_ATTACK", m.getOriginVillage().getName(), m.getArrivesAt(), Map.of(),
                                m.getOriginVillage().getId(), m.getTargetVillage().getId(), m.getDepartedAt(), 0, 0, 0,
                                m.getOriginVillage().getX(), m.getOriginVillage().getY(), null, m.getTargetLabel())
                        : new MovementDto(m.getId(), m.getType() == MovementType.RETURN ? "RETURNING" : "SUPPORT_IN",
                                m.getOriginVillage().getName(), m.getArrivesAt(), stringifyUnits(m.getUnits()),
                                m.getOriginVillage().getId(), m.getTargetVillage().getId(), m.getDepartedAt(),
                                m.getCarriedWood(), m.getCarriedClay(), m.getCarriedIron(),
                                m.getOriginVillage().getX(), m.getOriginVillage().getY(), null, m.getTargetLabel()))
                .toList();
    }

    private double perHour(Village village, BuildingType mine) {
        return mine.productionPerHour(villageService.levelOf(village, mine)) * BonusType.productionFactor(village, mine) * settings.speedOf(village);
    }

    // Overviews: a row for each of the player's villages (see OverviewDto)
    @Transactional(readOnly = true) // display only, see toDto
    public List<OverviewDto> villageOverview() {
        Village current = playerHomeVillage();
        List<OverviewDto> out = new java.util.ArrayList<>();
        for (Village v : villageRepository.findByWorldAndOwner(current.getWorld(), current.getOwner())) {
            villageService.settleResources(v);
            int points = 0;
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (BuildingType type : BuildingType.values()) levels.put(type.name(), 0);
            for (Building b : buildingRepository.findByVillage(v)) {
                points += b.getType().points(b.getLevel());
                levels.put(b.getType().name(), b.getLevel());
            }
            List<OverviewDto.QueuedBuilding> queue = buildQueueItemRepository.findByVillageOrderByPositionAsc(v).stream()
                    .map(q -> new OverviewDto.QueuedBuilding(q.getType().name(), q.getTargetLevel(), q.isDemolish(), q.getCompletesAt()))
                    .toList();
            Map<String, Integer> recruiting = new LinkedHashMap<>();
            for (TrainQueueItem t : trainQueueItemRepository.findByVillageOrderByPositionAsc(v)) {
                if (!t.isDecommission()) recruiting.merge(t.getType().name(), t.getTotalCount() - t.getProducedCount(), Integer::sum);
            }
            List<String> researched = ResearchService.TECHS.keySet().stream()
                    .filter(u -> researchService.isResearched(v, u)).map(UnitType::name).toList();
            List<String> researching = researchService.queueOf(v).stream().map(q -> q.getUnit().name()).toList();
            Map<String, Integer> home = new LinkedHashMap<>();
            for (UnitStock stock : unitStockRepository.findByVillage(v)) home.put(stock.getType().name(), stock.getCount());
            Map<String, Integer> support = new LinkedHashMap<>();
            supportService.guestsAt(v).forEach(g -> g.getUnits().forEach((t, n) -> support.merge(t.name(), n, Integer::sum)));
            Map<String, Integer> away = new LinkedHashMap<>();
            supportService.stationedFrom(v).forEach(g -> g.getUnits().forEach((t, n) -> away.merge(t.name(), n, Integer::sum)));

            List<Movement> movements = movementRepository.findByOriginVillageOrTargetVillage(v, v);
            List<MovementDto> commands = new java.util.ArrayList<>();
            List<MovementDto> incoming = new java.util.ArrayList<>();
            Map<String, Integer> moving = new LinkedHashMap<>();
            for (Movement m : movements) {
                boolean sent = m.getType() != MovementType.RETURN && m.getOriginVillage().getId().equals(v.getId());
                boolean back = m.getType() == MovementType.RETURN && m.getTargetVillage().getId().equals(v.getId());
                if (sent) commands.add(toOutgoingDto(m));
                if (sent || back) m.getUnits().forEach((t, n) -> moving.merge(t.name(), n, Integer::sum));
            }
            for (MovementDto m : incomingDtos(v, movements)) {
                if ("RETURNING".equals(m.type())) commands.add(m);
                else incoming.add(m);
            }
            commands.sort(java.util.Comparator.comparing(MovementDto::arrivesAt));
            incoming.sort(java.util.Comparator.comparing(MovementDto::arrivesAt));

            out.add(new OverviewDto(v.getId(), v.getName(), v.getX(), v.getY(), points, bonusCode(v),
                    v.getWood(), v.getClay(), v.getIron(), villageService.warehouseCapacity(v),
                    perHour(v, BuildingType.TIMBER_CAMP), perHour(v, BuildingType.CLAY_PIT), perHour(v, BuildingType.IRON_MINE),
                    villageService.populationUsed(v), villageService.populationCapacity(v),
                    marketService.availableMerchants(v), marketService.totalMerchants(v),
                    levels, queue, recruiting, researched, researching, home, support, away, moving, commands, incoming));
        }
        out.sort(java.util.Comparator.comparing(OverviewDto::name).thenComparing(OverviewDto::id));
        return out;
    }

    private MovementDto toOutgoingDto(Movement m) {
        return new MovementDto(m.getId(), m.getType() == MovementType.SUPPORT ? "SUPPORTING" : "ATTACKING", m.getTargetVillage().getName(), m.getArrivesAt(), stringifyUnits(m.getUnits()),
                m.getOriginVillage().getId(), m.getTargetVillage().getId(), m.getDepartedAt(), 0, 0, 0,
                m.getTargetVillage().getX(), m.getTargetVillage().getY(),
                MovementService.cancelUntil(m, settings.speedOf(m.getOriginVillage())), m.getLabel());
    }

    @Transactional(readOnly = true)
    public List<ReportDto> listReports() {
        return combatReportRepository.findByWorldIdAndAccountIdOrderByOccurredAtDesc(currentWorld().getId(), AccountContext.get().getId()).stream()
                .map(this::toReportDto)
                .toList();
    }

    public static final int MAX_DELETE = 1000;

    // Deletes the player's own reports in this world (ids of other players' reports are ignored); returns how many.
    @Transactional
    public int deleteReports(List<Long> ids) {
        Account me = AccountContext.get();
        if (me == null) throw new AuthService.AuthException("Please log in.");
        if (ids == null || ids.isEmpty()) return 0;
        if (ids.size() > MAX_DELETE) throw new IllegalArgumentException("Delete at most " + MAX_DELETE + " reports at a time.");
        List<CombatReport> mine = combatReportRepository.findByIdInAndWorldIdAndAccountId(ids, currentWorld().getId(), me.getId());
        combatReportRepository.deleteAll(mine);
        return mine.size();
    }

    private ReportDto toReportDto(CombatReport r) {
        Map<String, Integer> d = r.getDetail();
        Map<String, Integer> attackerUnits = section(d, "att:");
        Map<String, Integer> defenderUnits = section(d, "def:");
        ReportDto.SpyDto spy = d.containsKey("spy_level")
                ? new ReportDto.SpyDto(d.get("spy_level"), d.getOrDefault("spy_wood", 0), d.getOrDefault("spy_clay", 0),
                        d.getOrDefault("spy_iron", 0), section(d, "spy_building:"), section(d, "spy_home:"), section(d, "spy_away:"))
                : null;
        return new ReportDto(
                r.getId(), r.getAttackerVillageName(), r.getDefenderVillageName(), r.getOutcome() == null ? null : r.getOutcome().name(),
                stringifyUnitCounts(r.getAttackerLosses()), stringifyUnitCounts(r.getDefenderLosses()),
                r.getLootWood(), r.getLootClay(), r.getLootIron(), r.getOccurredAt(),
                r.getLoyaltyFrom(), r.getLoyaltyTo(), r.isConquered(),
                d.isEmpty() ? null : attackerUnits, d.isEmpty() ? null : defenderUnits,
                d.get("luck"), d.get("morale"), d.get("wall_before"), d.get("wall_after"),
                section(d, "dmg:"), d.get("capacity"), spy,
                r.isDefenderView(), r.getAttackerPlayer(), r.getDefenderPlayer(),
                d.containsKey("att_village") ? d.get("att_village").longValue() : null,
                d.containsKey("def_village") ? d.get("def_village").longValue() : null,
                d.get("att_x"), d.get("att_y"), d.get("def_x"), d.get("def_y"), d.get("night"),
                ReportService.category(d),
                ReportService.event(d), section(d, "back_"));
    }

    private static Map<String, Integer> section(Map<String, Integer> detail, String prefix) {
        Map<String, Integer> out = new LinkedHashMap<>();
        detail.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), v); });
        return out;
    }

    private Map<String, Integer> stringifyUnitCounts(Map<UnitType, Integer> units) {
        Map<String, Integer> out = new LinkedHashMap<>();
        units.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    private Map<String, Integer> stringifyUnits(Map<UnitType, Integer> units) {
        Map<String, Integer> out = new LinkedHashMap<>();
        units.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }
}
