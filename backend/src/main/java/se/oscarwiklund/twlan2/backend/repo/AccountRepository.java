package se.oscarwiklund.twlan2.backend.repo;

import se.oscarwiklund.twlan2.backend.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByUsernameLower(String usernameLower);
    java.util.List<Account> findByNpc(Boolean npc);
    // Only this column, so a request can't write back a stale copy of the rest of the account.
    @Modifying(flushAutomatically = true)
    @Query("update Account a set a.lastLoginAt = :at where a.id = :id")
    int touchLastLogin(@Param("id") Long id, @Param("at") Instant at);
}
