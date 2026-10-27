package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.OwnerType;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.domain.World;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.repo.WorldRepository;
import se.oscarwiklund.twlan2.backend.service.npc.NpcRetirement;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

// World "leftVillagesGrow": a real account that hasn't been used for that many real-world minutes (lastLoginAt: a
// login, or any request with its token, see AuthService.resolve) loses its
// villages to the barbarians (user's chosen design, not a passive NPC-style build-up loop, since the original's
// exact behaviour is compiled and not discoverable). 0/unset disables the sweep for that world.
// NPC accounts follow their own, unrelated clock: NpcRetirement decides which ones ever stop playing and when;
// once retired, this same sweep turns their villages barbarian too, regardless of leftVillagesGrow.
@Service
public class AbandonmentService {

    private final WorldRepository worlds;
    private final VillageRepository villages;
    private final MarketService market;
    private final PlayerWarService playerWars;

    private final GameLock gameLock;

    public AbandonmentService(WorldRepository worlds, VillageRepository villages, MarketService market, PlayerWarService playerWars, GameLock gameLock) {
        this.gameLock = gameLock;
        this.worlds = worlds;
        this.villages = villages;
        this.market = market;
        this.playerWars = playerWars;
    }

    @Scheduled(fixedDelayString = "${game.abandon-sweep-ms:60000}", initialDelay = 30000)
    @Transactional
    public void sweep() {
        gameLock.holdForTransaction(); // until this sweep has committed
        Instant now = Instant.now();
        for (World world : worlds.findAll()) {
            double minutes = WorldSettings.number(world, "leftVillagesGrow");
            for (Village v : villages.findByWorldAndOwnerType(world, OwnerType.PLAYER)) {
                Account owner = v.getOwner();
                if (owner == null) continue;
                boolean abandoned = owner.isNpc()
                        ? NpcRetirement.isRetired(owner, world, now)
                        : minutes > 0 && owner.getLastLoginAt() != null && Duration.between(owner.getLastLoginAt(), now).toMinutes() >= minutes;
                if (!abandoned) continue;
                if (owner.isNpc()) playerWars.leaveWorld(world.getId(), owner.getId()); // a retired NPC fights no wars
                market.dropVillage(v.getId()); // its offers go, so nobody trades with an empty village
                v.setOwner(null);
                v.setOwnerType(OwnerType.BARBARIAN);
                villages.save(v);
            }
        }
    }
}
