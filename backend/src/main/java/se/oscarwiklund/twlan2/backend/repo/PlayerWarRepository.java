package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.PlayerWar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerWarRepository extends JpaRepository<PlayerWar, Long> {
    List<PlayerWar> findByWorldId(Long worldId);
    List<PlayerWar> findByWorldIdAndPlayerAccountId(Long worldId, Long playerAccountId);
    Optional<PlayerWar> findByWorldIdAndPlayerAccountIdAndNpcAccountId(Long worldId, Long playerAccountId, Long npcAccountId);
    void deleteByPlayerAccountId(Long playerAccountId);
    void deleteByNpcAccountId(Long npcAccountId);
    void deleteByWorldId(Long worldId);
    void deleteByWorldIdAndPlayerAccountId(Long worldId, Long playerAccountId);
    void deleteByWorldIdAndNpcAccountId(Long worldId, Long npcAccountId);
}
