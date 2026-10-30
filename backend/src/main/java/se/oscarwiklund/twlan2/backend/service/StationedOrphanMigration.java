package se.oscarwiklund.twlan2.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import se.oscarwiklund.twlan2.backend.domain.MigrationMark;
import se.oscarwiklund.twlan2.backend.domain.OwnerType;
import se.oscarwiklund.twlan2.backend.domain.StationedTroops;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.repo.MigrationMarkRepository;
import se.oscarwiklund.twlan2.backend.repo.StationedTroopsRepository;

import java.util.List;

// One-time (#130): villages that fell to the barbarians before that sorted their armies (SupportService.villageAbandoned)
// still have them stationed abroad, counted against nobody's farm: they are lost now, as they would have been then.
// Support stationed IN barbarian villages is left alone: with open support targets a player may have sent it on purpose.
@Component
public class StationedOrphanMigration implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StationedOrphanMigration.class);
    static final String MARK = "stationed-orphans-1";

    private final StationedTroopsRepository stationed;
    private final MigrationMarkRepository marks;
    private final GameLock gameLock;

    public StationedOrphanMigration(StationedTroopsRepository stationed, MigrationMarkRepository marks, GameLock gameLock) {
        this.stationed = stationed;
        this.marks = marks;
        this.gameLock = gameLock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        gameLock.holdForTransaction(); // the tick may already be running
        if (marks.existsById(MARK)) return;
        int sorted = sortOrphans();
        marks.save(new MigrationMark(MARK));
        if (sorted > 0) LOG.info("Removed {} stationed armies whose village no player owns", sorted);
    }

    int sortOrphans() {
        List<StationedTroops> orphans = stationed.findAll().stream().filter(s -> ownerless(s.getOriginVillage())).toList();
        stationed.deleteAll(orphans);
        return orphans.size();
    }

    private static boolean ownerless(Village v) {
        return v.getOwner() == null || v.getOwnerType() != OwnerType.PLAYER;
    }
}
