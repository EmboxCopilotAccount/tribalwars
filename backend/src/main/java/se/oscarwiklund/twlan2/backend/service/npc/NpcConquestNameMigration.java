package se.oscarwiklund.twlan2.backend.service.npc;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.MigrationMark;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MigrationMarkRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.GameLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// One-time (#128): villages NPCs conquered before they renamed their conquests still carry the old owner's name
// ("<player>'s village", "Abandoned Camp"). Each gets the NPC's next name from NpcVillageNames, oldest village first.
@Component
public class NpcConquestNameMigration implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(NpcConquestNameMigration.class);
    static final String MARK = "npc-village-names-1";

    private final AccountRepository accounts;
    private final VillageRepository villages;
    private final MigrationMarkRepository marks;
    private final GameLock gameLock;

    public NpcConquestNameMigration(AccountRepository accounts, VillageRepository villages, MigrationMarkRepository marks, GameLock gameLock) {
        this.accounts = accounts;
        this.villages = villages;
        this.marks = marks;
        this.gameLock = gameLock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        gameLock.holdForTransaction(); // the tick may already be running
        if (marks.existsById(MARK)) return;
        int renamed = 0;
        for (Account npc : accounts.findByNpc(true)) {
            List<Village> owned = villages.findByOwner(npc).stream().sorted(Comparator.comparing(Village::getId)).toList();
            Set<String> inUse = new HashSet<>();
            for (Village v : owned) if (NpcVillageNames.isOwnName(npc, v.getName())) inUse.add(v.getName());
            for (Village v : owned) {
                if (NpcVillageNames.isOwnName(npc, v.getName())) continue;
                String name = NpcVillageNames.nextFor(npc, inUse);
                inUse.add(name);
                v.setName(name);
                villages.save(v);
                renamed++;
            }
        }
        marks.save(new MigrationMark(MARK));
        if (renamed > 0) LOG.info("Gave {} villages conquered by NPCs their conqueror's name", renamed);
    }
}
