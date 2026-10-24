package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.StationedTroops;
import se.oscarwiklund.twlan2.backend.domain.Village;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StationedTroopsRepository extends JpaRepository<StationedTroops, Long> {
    List<StationedTroops> findByHostVillage(Village host);
    List<StationedTroops> findByOriginVillage(Village origin);
    // A village's troops stationed as support in other villages, summed per unit type.
    @Query("select key(u), sum(u) from StationedTroops s join s.units u where s.originVillage = :v group by key(u)")
    List<Object[]> sumUnitsStationedAway(@Param("v") Village origin);
    Optional<StationedTroops> findByHostVillageAndOriginVillage(Village host, Village origin);
}
