package se.oscarwiklund.twlan2.backend.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.BuildingRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.StationedTroopsRepository;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;
import se.oscarwiklund.twlan2.backend.service.BuildService;
import se.oscarwiklund.twlan2.backend.service.TrainService;
import se.oscarwiklund.twlan2.backend.service.VillageService;
import se.oscarwiklund.twlan2.backend.service.WorldService;
import se.oscarwiklund.twlan2.backend.service.WorldSettings;
import se.oscarwiklund.twlan2.backend.web.dto.VillageStateDto;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Population and build costs as the village screens see them must match what the server checks and charges.
@SpringBootTest
@Transactional
class BuildAndPopulationTest {

    @Autowired WorldService worlds;
    @Autowired AccountRepository accounts;
    @Autowired BuildingRepository buildings;
    @Autowired UnitStockRepository stocks;
    @Autowired VillageService villageService;
    @Autowired TrainService train;
    @Autowired BuildService build;
    @Autowired GameFacade facade;
    @Autowired MovementRepository movements;
    @Autowired StationedTroopsRepository stationed;

    World world;
    Village village;

    @BeforeEach
    void setUp() {
        world = worlds.createWorld("build-pop-test", 1);
        Account player = new Account();
        player.setUsername("buildpoptester");
        player.setPasswordHash("x");
        accounts.save(player);
        AccountContext.set(player);
        WorldContext.set(world.getId());
        village = worlds.joinWorld(world, player);
    }

    @AfterEach
    void clearContext() {
        AccountContext.clear();
        WorldContext.clear();
    }

    private void setLevel(BuildingType type, int level) {
        Building b = buildings.findByVillageAndType(village, type).orElseGet(() -> {
            Building n = new Building();
            n.setVillage(village);
            n.setType(type);
            return n;
        });
        b.setLevel(level);
        buildings.save(b);
    }

    @Test
    void dismissingTroopsDoesNotCountThemTwice() {
        setLevel(BuildingType.BARRACKS, 1);
        UnitStock spears = new UnitStock();
        spears.setVillage(village);
        spears.setType(UnitType.SPEAR);
        spears.setCount(50);
        stocks.save(spears);
        int before = villageService.populationUsed(village);

        train.decommission(village, UnitType.SPEAR, 20);

        assertEquals(before, villageService.populationUsed(village)); // the 20 still count once, until they leave
    }

    private void fillResources() {
        setLevel(BuildingType.WAREHOUSE, 20);
        setLevel(BuildingType.FARM, 20);
        double cap = villageService.warehouseCapacity(village);
        village.setWood(cap);
        village.setClay(cap);
        village.setIron(cap);
    }

    private static VillageStateDto.BuildingDto dtoOf(VillageStateDto state, BuildingType type) {
        return state.buildings().stream().filter(b -> b.type().equals(type.name())).findFirst().orElseThrow();
    }

    @Test
    void shownCostIncludesTheQueueSurcharge() {
        fillResources();
        for (int i = 0; i < 5; i++) build.enqueue(village, BuildingType.TIMBER_CAMP); // the free slots

        VillageStateDto.BuildingDto shown = dtoOf(facade.toDto(village), BuildingType.CLAY_PIT);
        int level = villageService.levelOf(village, BuildingType.CLAY_PIT) + 1;
        assertTrue(shown.nextWood() > BuildingType.CLAY_PIT.woodCost(level)); // the 6th order costs extra

        double wood = village.getWood(), clay = village.getClay(), iron = village.getIron();
        build.enqueue(village, BuildingType.CLAY_PIT);
        assertEquals(shown.nextWood(), wood - village.getWood(), 1.0); // what was shown is what was charged
        assertEquals(shown.nextClay(), clay - village.getClay(), 1.0);
        assertEquals(shown.nextIron(), iron - village.getIron(), 1.0);
    }

    @Test
    void travelTimesOnScreenUseTheUnitSpeed() {
        village.getWorld().getSettings().put("unitsSpeed", "2");
        VillageStateDto state = facade.toDto(village);
        assertEquals(state.worldSpeed() * 2, state.travelSpeed(), 1e-9); // what MovementService sends troops at
    }

    @Test
    void queuedOrdersCarryTheFarmSpaceTheyReserve() {
        fillResources();
        setLevel(BuildingType.HEADQUARTERS, 3);
        build.enqueue(village, BuildingType.BARRACKS); // barracks 0 -> 1 takes farm space

        VillageStateDto.BuildQueueDto queued = facade.toDto(village).buildQueue().get(0);
        assertTrue(queued.popIncrease() > 0);
        assertEquals(BuildingType.BARRACKS.popIncrease(1), queued.popIncrease());
    }

    private void move(Village from, Village to, MovementType type, Map<UnitType, Integer> units) {
        Movement m = new Movement();
        m.setOriginVillage(from);
        m.setTargetVillage(to);
        m.setType(type);
        m.getUnits().putAll(units);
        m.setDepartedAt(Instant.now());
        m.setArrivesAt(Instant.now().plusSeconds(3600));
        movements.save(m);
    }

    @Test
    void troopsAwayFromHomeStillTakeFarmSpace() {
        Account other = new Account();
        other.setUsername("buildpopneighbour");
        other.setPasswordHash("x");
        accounts.save(other);
        Village neighbour = worlds.joinWorld(world, other);
        int before = villageService.populationUsed(village);

        move(village, neighbour, MovementType.ATTACK, Map.of(UnitType.AXE, 30));   // out on a command
        move(neighbour, village, MovementType.RETURN, Map.of(UnitType.LIGHT, 5));  // on the way back home
        StationedTroops support = new StationedTroops();
        support.setOriginVillage(village);
        support.setHostVillage(neighbour);
        support.getUnits().put(UnitType.SPEAR, 10);                               // stationed as support
        stationed.save(support);
        move(neighbour, village, MovementType.ATTACK, Map.of(UnitType.AXE, 100)); // someone else's troops: no

        int away = 30 * UnitType.AXE.popCost + 5 * UnitType.LIGHT.popCost + 10 * UnitType.SPEAR.popCost;
        assertEquals(before + away, villageService.populationUsed(village));
        assertEquals(before + away, facade.toDto(village).populationUsed());
    }

    @Test
    void wallScreenGetsTheBasicDefenceCombatAdds() {
        assertEquals(WorldSettings.number(world, "basicDefense"), facade.toDto(village).basicDefense());
    }
}
