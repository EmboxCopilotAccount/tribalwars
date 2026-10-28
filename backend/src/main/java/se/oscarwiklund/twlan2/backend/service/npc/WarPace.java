package se.oscarwiklund.twlan2.backend.service.npc;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.Movement;
import se.oscarwiklund.twlan2.backend.domain.MovementType;
import se.oscarwiklund.twlan2.backend.domain.UnitType;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.service.WorldSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// How hard NPCs at war may hit a real player: at most `warAttacksPerHour` of their attacks land on the player's villages
// within any hour, all NPCs together (a war with a whole NPC tribe does not multiply it). Counted by arrival, so the
// attacks held back by the night do not all land at once in the morning. Scouts do not count; a noble train (waves from
// one village landing within seconds) counts once. Attacks on the road come from the world view (they survive a
// restart); the ones that already landed are remembered here for an hour (a restart forgets those).
@Component
public class WarPace {

    static final Duration HOUR = Duration.ofHours(1);
    private static final long TRAIN_GAP_MS = 2000;

    // player id -> arrival times of the war attacks sent at them (one per noble train), dropped an hour after they landed
    private final Map<Long, List<Instant>> sent = new ConcurrentHashMap<>();

    // Whether one more attack landing on the player at `arrival` keeps every hour around it within the limit.
    public boolean allows(WorldView view, Long playerId, Instant arrival, Instant now) {
        int limit = (int) WorldSettings.number(view.world, "warAttacksPerHour");
        if (limit <= 0) return true;
        List<Instant> times = landing(view, playerId, arrival.minus(HOUR), arrival.plus(HOUR), now);
        times.add(arrival);
        Collections.sort(times);
        // the busiest hour that contains `arrival` starts at one of the arrivals up to an hour before it
        for (Instant start : times) {
            if (start.isAfter(arrival)) break;
            if (!start.isAfter(arrival.minus(HOUR))) continue;
            Instant end = start.plus(HOUR);
            int n = 0;
            for (Instant t : times) if (!t.isBefore(start) && t.isBefore(end)) n++;
            if (n > limit) return false;
        }
        return true;
    }

    public void record(Long playerId, Instant arrival) {
        Instant old = Instant.now().minus(HOUR);
        List<Instant> list = sent.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.removeIf(t -> t.isBefore(old));
            list.add(arrival);
        }
    }

    // Arrivals of NPC war attacks on the player between `from` and `to`: on the road (from the view) and already landed.
    List<Instant> landing(WorldView view, Long playerId, Instant from, Instant to, Instant now) {
        Map<Long, List<Instant>> byOrigin = new HashMap<>();
        for (Village v : view.villagesOfOwner(playerId)) {
            for (Movement m : view.targeting(v)) {
                if (m.getType() != MovementType.ATTACK || !fights(m.getUnits())) continue;
                Account sender = m.getOriginVillage().getOwner();
                if (sender == null || !sender.isNpc()) continue;
                Instant at = m.getArrivesAt();
                if (!at.isAfter(now) || at.isBefore(from) || at.isAfter(to)) continue; // landed ones are counted below
                byOrigin.computeIfAbsent(m.getOriginVillage().getId(), k -> new ArrayList<>()).add(at);
            }
        }
        List<Instant> out = new ArrayList<>();
        for (List<Instant> times : byOrigin.values()) {
            Collections.sort(times);
            Instant last = null;
            for (Instant t : times) {
                if (last == null || t.toEpochMilli() - last.toEpochMilli() > TRAIN_GAP_MS) out.add(t); // a new attack, not the next wave of a train
                last = t;
            }
        }
        List<Instant> landed = sent.get(playerId);
        if (landed != null) {
            synchronized (landed) {
                for (Instant t : landed) if (!t.isAfter(now) && !t.isBefore(from)) out.add(t);
            }
        }
        return out;
    }

    private static boolean fights(Map<UnitType, Integer> units) {
        for (var e : units.entrySet()) if (e.getKey() != UnitType.SCOUT && e.getValue() > 0) return true;
        return false;
    }
}
