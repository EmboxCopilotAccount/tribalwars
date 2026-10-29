package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.MapGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MapGroupRepository extends JpaRepository<MapGroup, Long> {
    List<MapGroup> findByWorldIdAndAccountIdOrderByIdAsc(Long worldId, Long accountId);
    List<MapGroup> findByWorldId(Long worldId);
    List<MapGroup> findByAccountId(Long accountId);
}
