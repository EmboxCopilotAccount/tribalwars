package se.oscarwiklund.twlan2.backend.web.dto;

import java.util.Map;

// catapultTarget: a BuildingType name the catapults aim at (attacks only), null or absent = random;
// label: the name given on the confirm page, null or absent = the default one
public record AttackRequest(Long targetVillageId, Map<String, Integer> units, String catapultTarget, String label) {
    public AttackRequest(Long targetVillageId, Map<String, Integer> units, String catapultTarget) {
        this(targetVillageId, units, catapultTarget, null);
    }
}
