package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.Movement;
import se.oscarwiklund.twlan2.backend.domain.MovementType;
import se.oscarwiklund.twlan2.backend.domain.Village;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {
    // in arrival order (ties: the order they were sent), so a clear timed just before its noblemen fights first
    List<Movement> findByArrivesAtLessThanEqualOrderByArrivesAtAscIdAsc(Instant now);
    List<Movement> findByOriginVillageOrTargetVillage(Village origin, Village target);
    List<Movement> findByOriginVillageAndTargetVillageAndTypeAndDepartedAtAfter(Village origin, Village target, MovementType type, Instant after);
}
