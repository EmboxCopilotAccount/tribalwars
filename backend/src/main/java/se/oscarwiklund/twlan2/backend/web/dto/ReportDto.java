package se.oscarwiklund.twlan2.backend.web.dto;

import java.time.Instant;
import java.util.Map;

// Everything after conquered is null/empty on reports written before it existed.
public record ReportDto(
        Long id,
        String attackerVillageName,
        String defenderVillageName,
        String outcome,
        Map<String, Integer> attackerLosses,
        Map<String, Integer> defenderLosses,
        double lootWood,
        double lootClay,
        double lootIron,
        Instant occurredAt,
        Integer loyaltyFrom,
        Integer loyaltyTo,
        boolean conquered,
        Map<String, Integer> attackerUnits,
        Map<String, Integer> defenderUnits,
        Integer luck,
        Integer morale,
        Integer wallBefore,
        Integer wallAfter,
        Map<String, Integer> buildingDamage,
        Integer lootCapacity,
        SpyDto spy,
        boolean defenderView,
        String attackerPlayer,
        String defenderPlayer,
        Long attackerVillageId,
        Long defenderVillageId,
        // village coordinates at the time of the battle
        Integer attackerX,
        Integer attackerY,
        Integer defenderX,
        Integer defenderY,
        // the night bonus as a percentage of the defence (100 by day, 200 = doubled); null on older reports
        Integer nightBonus,
        // "BATTLE", "SUPPORT" or "TRADE" (ReportService); event: SUPPORT_ARRIVED / SUPPORT_SENT_BACK /
        // SUPPORT_WITHDRAWN / TRADE_DELIVERED, null for battles. Support/trade reports have no outcome.
        String kind,
        String event,
        // resources the merchants carry home (an accepted market offer), by "wood"/"clay"/"iron"
        Map<String, Integer> tradeBack
) {
    // What the surviving scouts saw (level 1 resources, 2 + buildings, 3 + troops at home, 4 + troops away).
    public record SpyDto(int level, int wood, int clay, int iron, Map<String, Integer> buildings,
                         Map<String, Integer> unitsHome, Map<String, Integer> unitsAway) {}
}
