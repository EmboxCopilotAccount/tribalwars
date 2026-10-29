package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.MapGroupEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MapGroupEntryRepository extends JpaRepository<MapGroupEntry, Long> {
    List<MapGroupEntry> findByGroupIdIn(Collection<Long> groupIds);
    List<MapGroupEntry> findByGroupId(Long groupId);
    void deleteByGroupId(Long groupId);
}
