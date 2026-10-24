package se.oscarwiklund.twlan2.backend.web.dto;

import java.util.Map;

// catapultTarget: a BuildingType name the catapults aim at (attacks only), null or absent = random
public record AttackRequest(Long targetVillageId, Map<String, Integer> units, String catapultTarget) {}
