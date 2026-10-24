package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.Movement;
import se.oscarwiklund.twlan2.backend.domain.MovementType;
import se.oscarwiklund.twlan2.backend.domain.Village;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {
    List<Movement> findByArrivesAtLessThanEqual(Instant now);
    List<Movement> findByOriginVillageOrTargetVillage(Village origin, Village target);
    // A village's own troops on the road, summed per unit type: commands it sent out and troops on their way back to it.
    @Query("select key(u), sum(u) from Movement m join m.units u where (m.originVillage = :v and m.type <> :ret)"
            + " or (m.targetVillage = :v and m.type = :ret) group by key(u)")
    List<Object[]> sumUnitsAway(@Param("v") Village village, @Param("ret") MovementType returnType);
    List<Movement> findByOriginVillageAndTargetVillageAndTypeAndDepartedAtAfter(Village origin, Village target, MovementType type, Instant after);
}
