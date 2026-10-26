package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.live.LiveUpdates;
import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class MovementService {

    private final LiveUpdates live;
    private final MovementRepository movementRepository;
    private final UnitStockRepository unitStockRepository;
    private final GameSettings settings;
    private final TribeService tribes;
    private final WorldPoints worldPoints;
    private final PlayerWarService playerWars;

    // World setting "fakeLimit": an attack carrying less total population than this counts as a fake. Not derivable
    // from the (compiled) original — a reasonable classic-Tribal-Wars-style approximation.
    private static final int FAKE_POP_THRESHOLD = 10;
    // "fakeLimit": at most this many fakes may be outstanding against the same target within a day. Approximation, see above.
    private static final int FAKE_LIMIT_PER_TARGET = 3;
    // World setting "farmRule": an attack is refused if the target's points are below the attacker's own points
    // divided by this. Approximation, see above.
    private static final double FARM_RULE_RATIO = 5.0;

    public MovementService(LiveUpdates live, MovementRepository movementRepository, UnitStockRepository unitStockRepository, GameSettings settings,
                           TribeService tribes, WorldPoints worldPoints, PlayerWarService playerWars) {
        this.live = live;
        this.playerWars = playerWars;
        this.tribes = tribes;
        this.settings = settings;
        this.movementRepository = movementRepository;
        this.unitStockRepository = unitStockRepository;
        this.worldPoints = worldPoints;
    }

    public static class MovementException extends RuntimeException {
        public MovementException(String message) { super(message); }
    }

    public static long travelSeconds(Village a, Village b, Map<UnitType, Integer> units) {
        return travelSeconds(a, b, units, 1.0);
    }

    public static long travelSeconds(Village a, Village b, Map<UnitType, Integer> units, double worldSpeed) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        int slowestMinutesPerField = units.keySet().stream()
                .mapToInt(t -> t.speedMinutesPerField)
                .max()
                .orElse(20);
        return Math.max(1, Math.round(distance * slowestMinutesPerField * 60 / worldSpeed));
    }

    @Transactional(noRollbackFor = MovementException.class)
    public Movement sendAttack(Village origin, Village target, Map<UnitType, Integer> requestedUnits, BuildingType catapultTarget) {
        Movement m = sendAttack(origin, target, requestedUnits);
        m.setCatapultTarget(catapultTarget);
        return m;
    }

    @Transactional(noRollbackFor = MovementException.class) // NPCs try things that may be refused
    public Movement sendAttack(Village origin, Village target, Map<UnitType, Integer> requestedUnits) {
        if (origin.getId().equals(target.getId())) {
            throw new MovementException("Cannot attack your own village");
        }
        if (isSameOwner(origin, target) && !WorldSettings.bool(origin.getWorld(), "attackSelf")) {
            throw new MovementException("Cannot attack your own village");
        }
        boolean self = isSameOwner(origin, target); // protection and the farm rule shield a player from others
        if (!self && underProtection(target)) {
            throw new MovementException("This player is under beginner protection and cannot be attacked yet");
        }
        if (!self && target.getOwnerType() == OwnerType.PLAYER && WorldSettings.bool(origin.getWorld(), "farmRule")) {
            int originPoints = worldPoints.byVillage(origin.getWorld()).getOrDefault(origin.getId(), 0);
            int targetPoints = worldPoints.byVillage(origin.getWorld()).getOrDefault(target.getId(), 0);
            if (targetPoints < originPoints / FARM_RULE_RATIO) {
                throw new MovementException("This village is too small to attack (farm rule)");
            }
        }
        if (WorldSettings.bool(origin.getWorld(), "fakeLimit") && totalPopulation(requestedUnits) < FAKE_POP_THRESHOLD) {
            Instant since = Instant.now().minusSeconds(86400);
            long recentFakes = movementRepository
                    .findByOriginVillageAndTargetVillageAndTypeAndDepartedAtAfter(origin, target, MovementType.ATTACK, since)
                    .stream()
                    .filter(m -> totalPopulation(m.getUnits()) < FAKE_POP_THRESHOLD)
                    .count();
            if (recentFakes >= FAKE_LIMIT_PER_TARGET) {
                throw new MovementException("Fake attack limit reached for this village");
            }
        }
        Movement m = send(origin, target, requestedUnits, MovementType.ATTACK);
        playerWars.onAttackSent(m); // a player's attack on an NPC declares war on it
        return m;
    }

    private static int totalPopulation(Map<UnitType, Integer> units) {
        return units.entrySet().stream()
                .mapToInt(e -> e.getKey().popCost * (e.getValue() == null ? 0 : e.getValue()))
                .sum();
    }

    // Real players (not NPCs) cannot be attacked until their account is older than the world's beginner protection.
    public static boolean underProtection(Village target) {
        Account owner = target.getOwner();
        if (owner == null || owner.isNpc() || owner.getCreatedAt() == null) return false;
        double minutes = WorldSettings.number(target.getWorld(), "beginnerProtection");
        return minutes > 0 && java.time.Duration.between(owner.getCreatedAt(), Instant.now()).toMinutes() < minutes;
    }

    // The troops stay stationed there, they do not return.
    @Transactional(noRollbackFor = MovementException.class)
    public Movement sendSupport(Village origin, Village target, Map<UnitType, Integer> requestedUnits) {
        if (origin.getId().equals(target.getId())) {
            throw new MovementException("The troops are already in this village");
        }
        if (!isSameOwner(origin, target)) {
            if (!canSupport(origin, target, tribes)) {
                throw new MovementException("You can only send support to your own villages and to villages of your tribe members");
            }
            if (requestedUnits.getOrDefault(UnitType.SNOB, 0) > 0) {
                throw new MovementException("Noblemen cannot be stationed in another player's village");
            }
        }
        return send(origin, target, requestedUnits, MovementType.SUPPORT);
    }

    /** World setting supportTargets = "tribe": support only to own villages and tribe members' (default "any": any village). */
    public static boolean supportTribeOnly(World world) {
        return "tribe".equals(WorldSettings.get(world, "supportTargets"));
    }

    /** Whether troops of `origin` may be stationed in `target`: checked when they are sent and again when they arrive. */
    public static boolean canSupport(Village origin, Village target, TribeService tribes) {
        return isSameOwner(origin, target) || !supportTribeOnly(origin.getWorld())
                || tribes.sameTribe(origin.getOwner(), target.getOwner(), origin.getWorld());
    }

    public static boolean isSameOwner(Village a, Village b) {
        return a.getOwner() != null && b.getOwner() != null && a.getOwner().getId().equals(b.getOwner().getId());
    }

    private Movement send(Village origin, Village target, Map<UnitType, Integer> requestedUnits, MovementType type) {
        boolean anyPositive = requestedUnits.values().stream().anyMatch(c -> c != null && c > 0);
        if (!anyPositive) {
            throw new MovementException("Select at least one unit to send");
        }

        if (type == MovementType.ATTACK && requestedUnits.getOrDefault(UnitType.SNOB, 0) > 0) {
            double distance = Math.hypot(origin.getX() - target.getX(), origin.getY() - target.getY());
            if (distance > WorldSettings.number(origin.getWorld(), "noblemanRange")) {
                throw new MovementException("The target is out of the noblemen's range");
            }
        }

        for (Map.Entry<UnitType, Integer> entry : requestedUnits.entrySet()) {
            int requested = entry.getValue() == null ? 0 : entry.getValue();
            if (requested <= 0) continue;
            int available = unitStockRepository.findByVillageAndType(origin, entry.getKey())
                    .map(UnitStock::getCount).orElse(0);
            if (requested > available) {
                throw new MovementException("Not enough " + entry.getKey().displayName() + " at home (have " + available + ")");
            }
        }

        for (Map.Entry<UnitType, Integer> entry : requestedUnits.entrySet()) {
            int requested = entry.getValue() == null ? 0 : entry.getValue();
            if (requested <= 0) continue;
            UnitStock stock = unitStockRepository.findByVillageAndType(origin, entry.getKey()).orElseThrow();
            stock.setCount(stock.getCount() - requested);
            unitStockRepository.save(stock);
        }

        long seconds = travelSeconds(origin, target, requestedUnits, settings.travelSpeedOf(origin));
        Instant now = Instant.now();

        Movement movement = new Movement();
        movement.setOriginVillage(origin);
        movement.setTargetVillage(target);
        movement.setType(type);
        movement.setSelfAttack(type == MovementType.ATTACK && isSameOwner(origin, target));
        requestedUnits.forEach((unitType, count) -> {
            if (count != null && count > 0) movement.getUnits().put(unitType, count);
        });
        movement.setDepartedAt(now);
        movement.setArrivesAt(now.plusSeconds(seconds));

        live.village(origin);
        live.village(target); // (the incoming attack of a real player shows up at once)
        return movementRepository.save(movement);
    }

    // How long after sending an attack or support can still be called off: the original's 10 minutes at speed 1,
    // divided by the world speed like every other duration, but never under a minute.
    public static final long CANCEL_SECONDS = 600;
    public static final long MIN_CANCEL_SECONDS = 60;

    public static long cancelSeconds(double worldSpeed) {
        return Math.max(MIN_CANCEL_SECONDS, Math.round(CANCEL_SECONDS / Math.max(worldSpeed, 1e-9)));
    }

    // Until when the sender can still call this command off, or null when it can't be (not an attack/support).
    public static Instant cancelUntil(Movement m, double worldSpeed) {
        if ((m.getType() != MovementType.ATTACK && m.getType() != MovementType.SUPPORT) || m.getDepartedAt() == null) return null;
        Instant until = m.getDepartedAt().plusSeconds(cancelSeconds(worldSpeed));
        return until.isBefore(m.getArrivesAt()) ? until : m.getArrivesAt();
    }

    // The sender calls a command off: the troops turn round where they are and walk home as long as they have
    // walked so far. Only within cancelSeconds of sending.
    public static final int LABEL_MAX = 60;

    /** A command name as typed -> what is stored: trimmed, no control characters, at most LABEL_MAX; blank = null. */
    public static String cleanLabel(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (s.isEmpty()) return null;
        return s.length() > LABEL_MAX ? s.substring(0, LABEL_MAX).strip() : s;
    }

    /**
     * Names a command in the actor's list: an own attack or support on its way (incoming = false), or a command heading
     * for one of the actor's villages (incoming = true). Each side's name is its own.
     */
    @Transactional
    public Movement rename(Account actor, Long movementId, boolean incoming, String label) {
        Movement m = movementRepository.findById(movementId).orElseThrow(() -> new MovementException("This command no longer exists"));
        Village side = incoming ? m.getTargetVillage() : m.getOriginVillage();
        if (actor == null || side == null || side.getOwner() == null || !side.getOwner().getId().equals(actor.getId())) {
            throw new MovementException("This is not your command");
        }
        if (!incoming && m.getType() != MovementType.ATTACK && m.getType() != MovementType.SUPPORT) {
            throw new MovementException("Only attacks and support can be renamed");
        }
        if (incoming) m.setTargetLabel(cleanLabel(label));
        else m.setLabel(cleanLabel(label));
        live.village(side);
        return movementRepository.save(m);
    }

    /** The name given on the confirm page, for a command just sent. */
    @Transactional
    public void nameNewCommand(Long movementId, String label) {
        String clean = cleanLabel(label);
        if (clean != null) movementRepository.findById(movementId).ifPresent(m -> m.setLabel(clean));
    }

    @Transactional
    public Movement cancel(Account actor, Long movementId) {
        Movement m = movementRepository.findById(movementId).orElseThrow(() -> new MovementException("This command no longer exists"));
        Village origin = m.getOriginVillage();
        if (m.getType() != MovementType.ATTACK && m.getType() != MovementType.SUPPORT) throw new MovementException("Only attacks and support can be cancelled");
        if (actor == null || origin.getOwner() == null || !origin.getOwner().getId().equals(actor.getId())) throw new MovementException("This is not your command");
        Instant now = Instant.now();
        long walked = m.getDepartedAt() == null ? Long.MAX_VALUE : java.time.Duration.between(m.getDepartedAt(), now).toSeconds();
        long window = cancelSeconds(settings.speedOf(origin));
        if (walked > window || !m.getArrivesAt().isAfter(now)) {
            throw new MovementException("A command can only be cancelled in the first "
                    + (window % 60 == 0 ? window / 60 + (window == 60 ? " minute" : " minutes") : window + " seconds"));
        }

        Movement back = new Movement();
        back.setOriginVillage(m.getTargetVillage());
        back.setTargetVillage(origin);
        back.setType(MovementType.RETURN);
        back.getUnits().putAll(m.getUnits());
        back.setTargetLabel(m.getLabel());
        back.setDepartedAt(now);
        back.setArrivesAt(now.plusSeconds(Math.max(1, walked)));
        movementRepository.delete(m);
        playerWars.onAttackCancelled(m); // the war this attack declared is called off with it, unless another one is on its way
        live.village(origin);
        live.village(m.getTargetVillage()); // the incoming attack disappears there
        return movementRepository.save(back);
    }
}
