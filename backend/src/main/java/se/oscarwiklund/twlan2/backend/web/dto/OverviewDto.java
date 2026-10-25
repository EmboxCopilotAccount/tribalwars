package se.oscarwiklund.twlan2.backend.web.dto;

import se.oscarwiklund.twlan2.backend.web.dto.VillageStateDto.MovementDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// One row of Overviews (Production, Troops, Commands, Incoming, Buildings, Research) for each of the player's villages.
// Units by backend type name. Troops: home = the village's own at home, support = other villages' stationed here,
// away = its own stationed in other villages, moving = its own on the road (commands out and on the way home).
// commands = its attacks and support on the way plus troops returning home; incoming = attacks and support coming in.
public record OverviewDto(
        Long id, String name, int x, int y, int points, Integer bonus,
        double wood, double clay, double iron, int warehouseCapacity,
        double woodPerHour, double clayPerHour, double ironPerHour,
        int populationUsed, int populationCapacity,
        int merchantsAvailable, int merchantsTotal,
        Map<String, Integer> buildings,
        List<QueuedBuilding> buildQueue,
        Map<String, Integer> recruiting,
        List<String> researched,
        List<String> researching,
        Map<String, Integer> home,
        Map<String, Integer> support,
        Map<String, Integer> away,
        Map<String, Integer> moving,
        List<MovementDto> commands,
        List<MovementDto> incoming
) {
    public record QueuedBuilding(String type, int targetLevel, boolean demolish, Instant completesAt) {}
}
