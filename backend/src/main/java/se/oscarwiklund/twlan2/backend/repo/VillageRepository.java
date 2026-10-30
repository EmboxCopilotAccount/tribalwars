package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.OwnerType;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.domain.World;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VillageRepository extends JpaRepository<Village, Long> {
    List<Village> findByOwnerType(OwnerType ownerType);
    List<Village> findByWorld(World world);
    List<Village> findByWorldIsNull();
    List<Village> findByOwnerTypeAndOwnerIsNull(OwnerType ownerType);
    List<Village> findByWorldAndOwner(World world, Account owner);
    List<Village> findByOwner(Account owner);
    List<Village> findByWorldAndOwnerType(World world, OwnerType ownerType);
    java.util.Optional<Village> findFirstByWorldAndXAndY(World world, int x, int y);

    // villages of an owner type that lack at least one building type (a village has each type at most once)
    @org.springframework.data.jpa.repository.Query("select v.id from Village v where v.ownerType = :ownerType"
            + " and (select count(b) from Building b where b.village = v) < :types")
    List<Long> idsMissingBuildings(@org.springframework.data.repository.query.Param("ownerType") OwnerType ownerType,
                                   @org.springframework.data.repository.query.Param("types") long types);
}
