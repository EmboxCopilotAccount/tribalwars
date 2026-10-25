package se.oscarwiklund.twlan2.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Entity
@Table(name = "movement")
public class Movement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "origin_village_id")
    private Village originVillage;

    @ManyToOne(optional = false)
    @JoinColumn(name = "target_village_id")
    private Village targetVillage;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    @ElementCollection
    @CollectionTable(name = "movement_units", joinColumns = @JoinColumn(name = "movement_id"))
    @MapKeyColumn(name = "unit_type")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "count")
    private Map<UnitType, Integer> units = new EnumMap<>(UnitType.class);

    private double carriedWood;
    private double carriedClay;
    private double carriedIron;

    private Instant departedAt;
    private Instant arrivesAt;

    // an attack on a village its sender already owned when it set off (attackSelf): it fights, unlike an attack on a
    // village the sender conquers while it is on the road, which moves in. Null on older rows = false.
    private Boolean selfAttack;

    // the building the attacker's catapults aim at; null = a random one
    @Enumerated(EnumType.STRING)
    private BuildingType catapultTarget;

    // the command's name as its sender (label) and the village it heads for (targetLabel) see it in their lists; null = the
    // default ("Attack on ..."). Kept apart so an attacker's name never reaches the defender.
    @Column(length = 60)
    private String label;
    @Column(length = 60)
    private String targetLabel;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getTargetLabel() { return targetLabel; }
    public void setTargetLabel(String targetLabel) { this.targetLabel = targetLabel; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BuildingType getCatapultTarget() { return catapultTarget; }
    public void setCatapultTarget(BuildingType catapultTarget) { this.catapultTarget = catapultTarget; }

    public boolean isSelfAttack() { return Boolean.TRUE.equals(selfAttack); }
    public void setSelfAttack(boolean selfAttack) { this.selfAttack = selfAttack; }

    public Village getOriginVillage() { return originVillage; }
    public void setOriginVillage(Village originVillage) { this.originVillage = originVillage; }

    public Village getTargetVillage() { return targetVillage; }
    public void setTargetVillage(Village targetVillage) { this.targetVillage = targetVillage; }

    public MovementType getType() { return type; }
    public void setType(MovementType type) { this.type = type; }

    public Map<UnitType, Integer> getUnits() { return units; }
    public void setUnits(Map<UnitType, Integer> units) { this.units = units; }

    public double getCarriedWood() { return carriedWood; }
    public void setCarriedWood(double carriedWood) { this.carriedWood = carriedWood; }

    public double getCarriedClay() { return carriedClay; }
    public void setCarriedClay(double carriedClay) { this.carriedClay = carriedClay; }

    public double getCarriedIron() { return carriedIron; }
    public void setCarriedIron(double carriedIron) { this.carriedIron = carriedIron; }

    public Instant getDepartedAt() { return departedAt; }
    public void setDepartedAt(Instant departedAt) { this.departedAt = departedAt; }

    public Instant getArrivesAt() { return arrivesAt; }
    public void setArrivesAt(Instant arrivesAt) { this.arrivesAt = arrivesAt; }
}
