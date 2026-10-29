package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.VillageNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VillageNoteRepository extends JpaRepository<VillageNote, Long> {
    Optional<VillageNote> findByVillageIdAndAccountId(Long villageId, Long accountId);
    void deleteByAccountId(Long accountId);
    void deleteByWorldId(Long worldId);
}
