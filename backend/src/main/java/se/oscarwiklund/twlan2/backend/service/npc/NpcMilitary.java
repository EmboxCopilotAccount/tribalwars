package se.oscarwiklund.twlan2.backend.service.npc;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.UnitStockRepository;
import se.oscarwiklund.twlan2.backend.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

// How an NPC village picks a fight: it looks at the villages in range, works out what it knows about each (a scouting report,
// the result of its last battle there, or a guess from the village's points), lets AttackPlanner decide what to send
// and sends it; when it does not know enough it sends scouts first. Farming NPCs return to targets that paid off and drop
// those that did not, and a grudge (see NpcIntelService) pulls an NPC toward whoever hit it. All orders go through
// MovementService, so the rules are the players' rules.
// Real players are only attacked at war (PlayerWarService, #118): then every village of the enemy is a target at any
// distance (the close ones first), the NPC acts on every step it gets, and a village near the enemy that cannot win
// safely sends a full assault or saves up for one instead of scouting again and again. War attacks are paced: at most
// warAttacksPerHour land on a player within any hour (WarPace, all NPCs together), none lands at night (warNoNightAttacks).
@Service
public class NpcMilitary {

    private static final List<UnitType> OFFENCE = List.of(UnitType.AXE, UnitType.LIGHT, UnitType.MARCHER, UnitType.RAM, UnitType.CATAPULT);
    // Scouts a village needs at home before it will scout (fewer survive too rarely to see the garrison).
    private static final int MIN_SCOUTS = 4;
    // War: at most this many of the enemy's villages are looked at per step (the closest).
    private static final int WAR_TARGETS = 10;
    // War tuning: a strike may cost up to this share of itself, and takes at least this share of the offence along.
    private static final double WAR_MAX_LOSS = 0.75, WAR_MIN_COMMIT = 0.9;
    // A full assault goes when on an average day it kills this share of the defenders, or its attack reaches this share of the defence.
    private static final double ASSAULT_KILL_SHARE = 0.3, ASSAULT_POWER_SHARE = 0.5;

    private final UnitStockRepository unitStock;
    private final MovementService movementService;
    private final NpcIntelService intel;
    private final NpcLogService npcLog;
    private final GameSettings settings;
    private final WarPace pace;
    // village -> the war target it is saving up for (so the NPC log notes it once, not on every step)
    private final Map<Long, Long> saving = new java.util.concurrent.ConcurrentHashMap<>();

    public NpcMilitary(UnitStockRepository unitStock, MovementService movementService,
                       NpcIntelService intel, NpcLogService npcLog, GameSettings settings, WarPace pace) {
        this.unitStock = unitStock;
        this.movementService = movementService;
        this.intel = intel;
        this.npcLog = npcLog;
        this.settings = settings;
        this.pace = pace;
    }

    private record Candidate(Village target, double weight, boolean human, boolean barbarian, boolean lastOfHuman) {}

    private record Conquest(Candidate c, ConquestPlan.Waves waves, double score) {}

    private record Strike(Candidate c, AttackPlanner.Plan plan, String basis, double score) {}

    private record Scouting(Candidate c, int scouts) {}

    // How the NPC's tribe sees the tribe of the village's owner (null: no tribe or no relation).
    private TribeRelation.Kind relationTo(Tribe mine, Village target, WorldView view) {
        if (mine == null || target.getOwner() == null) return null;
        Tribe other = view.tribeByAccount.get(target.getOwner().getId());
        return other == null ? null : view.relation(mine.getId(), other.getId());
    }

    // Whether the NPC village may attack `t` at all: not its own, its tribe's, a partner's or NAP's, and not protected.
    private boolean attackable(Village v, Village t, Tribe myTribe, WorldView view) {
        if (t.getId().equals(v.getId()) || MovementService.isSameOwner(v, t)) return false;
        TribeRelation.Kind kind = relationTo(myTribe, t, view);
        if (kind == TribeRelation.Kind.PARTNER || kind == TribeRelation.Kind.NAP) return false;
        Tribe theirs = t.getOwner() == null ? null : view.tribeByAccount.get(t.getOwner().getId());
        if (myTribe != null && theirs != null && theirs.getId().equals(myTribe.getId())) return false;
        return !MovementService.underProtection(t);
    }

    private static double distance(Village a, Village b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    // `share` of each offensive unit at home (so a raid never empties the village).
    private static Map<UnitType, Integer> committed(Map<UnitType, Integer> home, double share) {
        Map<UnitType, Integer> army = new EnumMap<>(UnitType.class);
        for (UnitType t : OFFENCE) {
            int n = (int) Math.floor(home.getOrDefault(t, 0) * share);
            if (n > 0) army.put(t, n);
        }
        return army;
    }

    @Transactional(noRollbackFor = MovementService.MovementException.class)
    public void step(Village v, NpcArchetype archetype, double skill, NpcDifficulty d, WorldView view, Random rnd) {
        World world = v.getWorld();
        if (world == null || !d.attacks()) return;
        if (view.waveOut(v)) return; // a wave (or scouts) is out

        Account me = v.getOwner();
        double speed = settings.speedOf(v);
        Instant now = Instant.now();

        // what it can send: the offensive troops (a share of them, so a raid never empties the village)
        Map<UnitType, Integer> home = new EnumMap<>(UnitType.class);
        for (UnitStock s : unitStock.findByVillage(v)) if (s.getCount() > 0) home.put(s.getType(), s.getCount());
        Map<UnitType, Integer> army = committed(home, d.commit());
        double power = 0;
        for (var e : army.entrySet()) power += (double) e.getKey().attack * e.getValue();
        int scouts = home.getOrDefault(UnitType.SCOUT, 0);
        int snobs = home.getOrDefault(UnitType.SNOB, 0);
        boolean scoutOnlyPossible = d.scouts() && scouts >= MIN_SCOUTS;

        // conquest: with enough noblemen at home for a whole chain of attacks the NPC does not wait for its raid mood
        NpcDifficulty.Expansion expansion = d.expansionFor(world);
        int need = ConquestPlan.wavesNeeded(100, (int) WorldSettings.number(world, "noblemanMinDecrease"),
                (int) WorldSettings.number(world, "noblemanMaxDecrease"), false);
        boolean conquestReady = expansion != NpcDifficulty.Expansion.NONE && snobs >= need;

        // war: the villages of the players at war with this NPC (or its tribe), the closest first, at any distance
        Tribe myTribe = view.tribeOf(me);
        List<Village> war = new ArrayList<>();
        for (Village t : view.warTargets(me.getId())) if (attackable(v, t, myTribe, view)) war.add(t);
        war.sort(Comparator.comparingDouble(t -> distance(v, t)));
        if (war.size() > WAR_TARGETS) war = new ArrayList<>(war.subList(0, WAR_TARGETS));
        boolean atWar = !war.isEmpty();
        if (!atWar) saving.remove(v.getId());

        double chance = d.raidChance() * archetype.drive() * (0.5 + skill);
        boolean mood = rnd.nextDouble() <= chance;
        if (!conquestReady && !mood && !atWar) return; // at war it acts on every step (no mood roll; WarPace limits what lands)
        boolean act = mood || rnd.nextDouble() < chance * 4; // a conquering NPC clears and scouts its target faster
        // raids on barbarians and NPCs (and conquests) keep their mood: a war does not make an NPC farm more
        boolean ordinary = (conquestReady || mood) && !(power < 400 && !(scoutOnlyPossible && power > 0) && !conquestReady);
        if (!ordinary && !atWar) return;

        Map<Long, NpcIntel> known = intel.of(me.getId());
        Map<Long, Double> grudges = d.retaliation() ? intel.grudgesOf(me.getId(), speed) : Map.of();
        double range = (12 + 20 * archetype.drive()) * (grudges.isEmpty() ? 1 : 1.4);

        double basicDefense = WorldSettings.number(world, "basicDefense");
        boolean moraleOn = !"off".equals(WorldSettings.get(world, "morale"));
        List<Strike> strikes = new ArrayList<>();
        List<Candidate> toScout = new ArrayList<>();
        List<Conquest> conquests = new ArrayList<>();

        if (ordinary) {
            List<Village> around = new ArrayList<>();
            for (Village t : view.near(v.getX(), v.getY(), range)) {
                if (distance(v, t) > range) continue;
                if (t.getOwner() != null && !t.getOwner().isNpc()) continue; // real players: only at war (below)
                if (attackable(v, t, myTribe, view)) around.add(t);
            }
            Collections.shuffle(around, rnd);

            for (Village t : around.subList(0, Math.min(10, around.size()))) {
                boolean barbarian = t.getOwnerType() == OwnerType.BARBARIAN;
                double grudge = t.getOwner() == null ? 0 : grudges.getOrDefault(t.getOwner().getId(), 0.0);
                if (view.underAttack(t)) continue;

                double w = barbarian ? 3.0 : 1.0;
                if (BonusType.of(t) != null) w *= 2;                                                        // a bonus village is public knowledge (map popup) and worth having
                if (relationTo(myTribe, t, view) == TribeRelation.Kind.ENEMY) w *= 3;
                if (grudge > 0) w *= 1 + 4 * grudge;
                NpcIntel i = known.get(t.getId());
                if (d.farming() && i != null) {
                    if ("LOST".equals(i.getLastResult()) && i.getLosses() >= 2) w *= 0.2;                        // it hurt us here
                    else if ("WON".equals(i.getLastResult()) && i.getRaids() > 0 && i.getLastLoot() < 40) w *= 0.15; // nothing left to take
                    else if ("WON".equals(i.getLastResult()) && i.getLastLoot() >= 200) w *= 1.5;               // pays well: come back
                }
                Candidate c = new Candidate(t, w, false, barbarian, false);
                boolean conquerable = conquestReady && expansion.allows(barbarian, false, false);
                if (conquerable) c = new Candidate(t, w * 2.5, false, barbarian, false); // clear it, then take it

                Map<UnitType, Integer> seen;
                int wall;
                double margin;
                String basis;
                if (NpcIntelService.fresh(i, speed, now)) {
                    seen = NpcIntelService.parse(i.getTroops());
                    wall = i.getWall() == null ? 0 : i.getWall();
                    margin = d.intelNoise();
                    basis = "scouted";
                } else if (scoutOnlyPossible) {
                    toScout.add(c);
                    continue;
                } else {
                    // no scouts (or none wanted): a guess from the public points, and a wide safety margin for it
                    double noise = 1 + d.intelNoise() * (2 * rnd.nextDouble() - 1) * 1.5;
                    int points = view.points(t);
                    seen = AttackPlanner.guessGarrison(points, noise);
                    wall = Math.min(15, points / 150);
                    margin = 0.3 + d.intelNoise();
                    basis = "guess";
                }

                double loot = i != null && i.getWood() != null ? 0.9 * (i.getWood() + i.getClay() + i.getIron())
                        : i != null && i.getLastLoot() > 0 ? 0.8 * i.getLastLoot() : 300;
                if (conquerable && basis.equals("scouted") && seen.isEmpty()) {
                    // cleared and known: this is the moment for the noblemen
                    var waves = ConquestPlan.waves(snobs, need, army, seen, wall, basicDefense, nightFor(v, t, NOBLE, now), 1, margin);
                    if (waves != null) {
                        conquests.add(new Conquest(c, waves, c.weight()));
                        continue;
                    }
                }
                boolean rams = wall > 0 && (d.farming() || (i != null && i.getRaids() > 0));
                Map<UnitType, Integer> seenHere = seen;
                int wallHere = wall;
                double marginHere = margin, lootHere = loot;
                var plan = atArrival(v, t, army, now, n -> AttackPlanner.plan(new EnumMap<>(army), seenHere, wallHere, basicDefense, n, 1,
                        marginHere, lootHere, d.farming() ? 0.3 : 0.5, rams));
                if (plan == null) continue;
                double travelHours = MovementService.travelSeconds(v, t, plan.send(), settings.travelSpeedOf(v)) / 3600.0;
                double score = w * (1 + Math.min(loot, plan.carry()) / 500.0) / (d.farming() ? 1 + travelHours : 1);
                strikes.add(new Strike(c, plan, basis, score));
            }
        }

        // ---- war ----
        // a village with one of the enemy's villages within its normal range fights the war in full (assaults, saving
        // up for them); farther ones only send attacks that win and otherwise go on raiding as usual
        boolean near = false;
        for (Village t : war) if (distance(v, t) <= range) { near = true; break; }
        Map<UnitType, Integer> warArmy = committed(home, Math.max(WAR_MIN_COMMIT, d.commit()));
        List<Strike> warStrikes = new ArrayList<>();
        List<Strike> assaults = new ArrayList<>();
        List<Scouting> warScouts = new ArrayList<>();
        Candidate savingFor = null;
        double warPower = attackPower(warArmy);
        boolean dayOnly = WorldSettings.bool(world, "warNoNightAttacks");
        for (Village t : war) {
            double dist = distance(v, t);
            boolean last = view.villageCount(t.getOwner()) <= 1;
            double grudge = grudges.getOrDefault(t.getOwner().getId(), 0.0);
            // the close villages first by a big margin: x4.3 at 3 fields, x1.5 at 15, x0.67 at 30, x0.11 at 100
            double w = d.humanWeight() * 6 * Math.pow(15 / (15 + dist), 2);
            if (BonusType.of(t) != null) w *= 2;
            if (relationTo(myTribe, t, view) == TribeRelation.Kind.ENEMY) w *= 3;
            if (grudge > 0) w *= 1 + 4 * grudge;
            boolean conquerable = conquestReady && expansion.allows(false, true, last);
            if (conquerable) w *= 2.5;
            Candidate c = new Candidate(t, w, true, false, last);
            NpcIntel i = known.get(t.getId());

            Map<UnitType, Integer> seen;
            int wall;
            double margin;
            String basis;
            if (NpcIntelService.fresh(i, speed, now)) {
                seen = NpcIntelService.parse(i.getTroops()); // scouted, or seen empty after a won battle
                wall = i.getWall() == null ? 0 : i.getWall();
                margin = d.intelNoise();
                basis = "scouted";
            } else if (NpcIntelService.saving(i, warPower, speed, now)) {
                // too strong when it was scouted, and the army has not grown much since: no new scouts (#123)
                seen = NpcIntelService.parse(i.getTroops());
                wall = i.getWall() == null ? 0 : i.getWall();
                margin = 0.3 + d.intelNoise();
                basis = "old report (saving)";
            } else {
                // its scouts are already on the way here (from any of its villages): wait for their report; else each of
                // its villages sent a party in the same tick, and dead ones counted as a burst of failed runs
                if (scoutingAt(t, me, view)) continue;
                int lost = i == null ? 0 : i.getLossStreak();
                int scoutWith = lost > 0 ? 0 : warScouts(d, i, me, t, scouts, skill, now);
                if (scoutWith > 0) {
                    warScouts.add(new Scouting(c, scoutWith));
                    continue;
                }
                // a guess from the points; after lost battles here it is bigger each time (x1.5 per loss in a row), not scouted again
                double noise = 1 + d.intelNoise() * (2 * rnd.nextDouble() - 1) * 1.5;
                int points = view.points(t);
                seen = AttackPlanner.guessGarrison(points, noise * Math.pow(1.5, lost));
                wall = i != null && i.getWall() != null ? i.getWall() : Math.min(15, points / 150);
                margin = 0.3 + d.intelNoise();
                basis = lost > 0 ? "guess after " + lost + (lost == 1 ? " loss" : " losses") : "guess";
            }
            margin /= 2; // at war it takes more risk

            double loot = i != null && i.getWood() != null ? 0.9 * (i.getWood() + i.getClay() + i.getIron())
                    : i != null && i.getLastLoot() > 0 ? 0.8 * i.getLastLoot() : 300;
            double morale = moraleOn ? BattleCalculator.morale(view.pointsOf(me), view.pointsOf(t.getOwner())) : 1;
            if (conquerable && basis.equals("scouted") && seen.isEmpty()
                    && !(dayOnly && landsAtNight(v, t, NOBLE, now)) && pace.allows(view, t.getOwner().getId(), arrival(v, t, NOBLE, now), now)) {
                var waves = ConquestPlan.waves(snobs, need, warArmy, seen, wall, basicDefense, nightFor(v, t, NOBLE, now), morale, margin);
                if (waves != null) {
                    conquests.add(new Conquest(c, waves, c.weight()));
                    continue;
                }
            }
            Map<UnitType, Integer> seenHere = seen;
            int wallHere = wall;
            double marginHere = margin, lootHere = loot;
            java.util.function.DoubleFunction<AttackPlanner.Plan> strikeWith = n -> AttackPlanner.plan(new EnumMap<>(warArmy), seenHere, wallHere,
                    basicDefense, n, morale, marginHere, lootHere, WAR_MAX_LOSS, wallHere > 0);
            var plan = atArrival(v, t, warArmy, now, strikeWith);
            if (plan != null) {
                plan = dayOnly ? byDay(v, t, plan, now, strikeWith) : plan;
                if (plan == null || !pace.allows(view, t.getOwner().getId(), arrival(v, t, plan.send(), now), now)) continue; // waits for the day / its turn
                double travelHours = MovementService.travelSeconds(v, t, plan.send(), settings.travelSpeedOf(v)) / 3600.0;
                warStrikes.add(new Strike(c, plan, "war, " + basis, w * (1 + Math.min(loot, plan.carry()) / 500.0) / (1 + travelHours)));
                continue;
            }
            // too strong on what the scouts saw: it saves up instead of scouting it again each time the report goes stale
            if (basis.equals("scouted") && !seen.isEmpty()) intel.tooStrong(me.getId(), t.getId(), warPower);
            if (!near) continue;
            java.util.function.DoubleFunction<AttackPlanner.Plan> assaultWith = n -> AttackPlanner.assault(warArmy, seenHere, wallHere, basicDefense, n,
                    morale, ASSAULT_KILL_SHARE, ASSAULT_POWER_SHARE);
            var all = atArrival(v, t, warArmy, now, assaultWith);
            if (all != null) {
                all = dayOnly ? byDay(v, t, all, now, assaultWith) : all;
                if (all == null || !pace.allows(view, t.getOwner().getId(), arrival(v, t, all.send(), now), now)) continue; // waits for the day / its turn
                double travelHours = MovementService.travelSeconds(v, t, all.send(), settings.travelSpeedOf(v)) / 3600.0;
                assaults.add(new Strike(c, all, "war, full assault, " + basis, w / (1 + travelHours)));
            } else if (savingFor == null || w > savingFor.weight()) {
                savingFor = c;
            }
        }

        if (!conquests.isEmpty()) {
            Conquest best = conquests.get(pickIndex(conquests.stream().map(Conquest::score).toList(), rnd));
            sendConquest(v, me, best, view);
            saving.remove(v.getId());
            return;
        }
        if (!warStrikes.isEmpty() || !warScouts.isEmpty() || !assaults.isEmpty()) {
            saving.remove(v.getId());
            if (!warStrikes.isEmpty()) {
                sendStrike(v, me, rnd.nextDouble() < skill ? Collections.max(warStrikes, Comparator.comparingDouble(Strike::score)) : weighted(warStrikes, rnd), view);
            } else if (!warScouts.isEmpty()) {
                Scouting s = warScouts.get(pickIndex(warScouts.stream().map(x -> x.c().weight()).toList(), rnd));
                sendScouts(v, me, s.c().target(), s.scouts(), view);
            } else {
                sendStrike(v, me, rnd.nextDouble() < skill ? Collections.max(assaults, Comparator.comparingDouble(Strike::score)) : weighted(assaults, rnd), view);
            }
            return;
        }
        if (near && savingFor != null) {
            // too weak for any assault on the enemy nearby: no raids elsewhere, the offence stays home and grows
            Village t = savingFor.target();
            if (!t.getId().equals(saving.put(v.getId(), t.getId()))) {
                npcLog.add(world, me, v, "SAVING", v.getName() + " is saving for war on " + t.getName() + " (" + t.getX() + "|" + t.getY() + ") of "
                        + t.getOwner().getUsername());
            }
            return;
        }
        saving.remove(v.getId());

        if (!ordinary || !act) return;
        if (!strikes.isEmpty()) {
            Strike s = rnd.nextDouble() < skill ? Collections.max(strikes, Comparator.comparingDouble(Strike::score)) : weighted(strikes, rnd);
            sendStrike(v, me, s, view);
        } else if (!toScout.isEmpty()) {
            Candidate c = toScout.get(pickIndex(toScout.stream().map(Candidate::weight).toList(), rnd));
            // enough scouts to beat the ones it expects at home (what an old report showed, else a guess for NPCs)
            NpcIntel old = known.get(c.target().getId());
            int expected = old != null && old.getTroops() != null ? NpcIntelService.parse(old.getTroops()).getOrDefault(UnitType.SCOUT, 0)
                    : c.barbarian() ? 0 : 8;
            int n = Math.min(scouts, Math.max((int) Math.round(4 + 6 * skill), (int) Math.ceil(1.5 * expected) + 3));
            sendScouts(v, me, c.target(), n, view);
        }
    }

    private static boolean scoutingAt(Village t, Account me, WorldView view) {
        for (Movement m : view.targeting(t)) {
            if (m.getType() == MovementType.ATTACK && me.getId().equals(m.getOriginVillage().getOwner().getId())
                    && m.getUnits().keySet().equals(Set.of(UnitType.SCOUT))) return true;
        }
        return false;
    }

    private static double attackPower(Map<UnitType, Integer> army) {
        double sum = 0;
        for (var e : army.entrySet()) sum += (double) e.getKey().attack * e.getValue();
        return sum;
    }

    private static final Map<UnitType, Integer> NOBLE = Map.of(UnitType.SNOB, 1); // the slowest unit of a conquest wave

    // The night bonus where these troops would arrive: the battle is fought at their arrival time (CombatService).
    private double nightFor(Village from, Village to, Map<UnitType, Integer> units, Instant now) {
        long seconds = MovementService.travelSeconds(from, to, units, settings.travelSpeedOf(from));
        return WorldSettings.nightFactor(from.getWorld(), now.plusSeconds(seconds));
    }

    private Instant arrival(Village from, Village to, Map<UnitType, Integer> units, Instant now) {
        return now.plusSeconds(MovementService.travelSeconds(from, to, units, settings.travelSpeedOf(from)));
    }

    private boolean landsAtNight(Village from, Village to, Map<UnitType, Integer> units, Instant now) {
        return WorldSettings.isNight(from.getWorld(), java.time.LocalTime.ofInstant(arrival(from, to, units, now), java.time.ZoneId.systemDefault()));
    }

    // Under warNoNightAttacks: a war plan that would land at night is swapped for one with fast troops that get there by
    // day, else null (the village waits for the day; the target is not "too strong", so it does not save up for it).
    private AttackPlanner.Plan byDay(Village from, Village to, AttackPlanner.Plan p, Instant now,
                                     java.util.function.DoubleFunction<AttackPlanner.Plan> planWith) {
        if (!landsAtNight(from, to, p.send(), now)) return p;
        AttackPlanner.Plan day = planWith.apply(1);
        return day != null && !landsAtNight(from, to, day.send(), now) ? day : null;
    }

    // Plans with the night bonus at the arrival of the troops the plan picks. First by the slowest unit of the army; if
    // the troops picked arrive at another hour, planned again for that hour. With nothing to send by night, it tries
    // daytime, for fast troops that would still get there before the night.
    private AttackPlanner.Plan atArrival(Village from, Village to, Map<UnitType, Integer> army, Instant now,
                                         java.util.function.DoubleFunction<AttackPlanner.Plan> planWith) {
        double first = nightFor(from, to, army, now);
        AttackPlanner.Plan p = planWith.apply(first);
        if (p != null) {
            double there = nightFor(from, to, p.send(), now);
            if (there <= first) return p; // as hard as planned, or easier
            p = planWith.apply(there);
            return p != null && nightFor(from, to, p.send(), now) <= there ? p : null;
        }
        if (first <= 1) return null;
        p = planWith.apply(1);
        return p != null && nightFor(from, to, p.send(), now) <= 1 ? p : null;
    }

    // How many scouts to send at a war target whose garrison it does not know; 0: it does not scout it now and attacks on
    // a guess instead (no scouts wanted or at home, scouting it is paused after two failed runs in a row, or too few
    // scouts at home for a retry, which takes at least twice as many as the failed run).
    private int warScouts(NpcDifficulty d, NpcIntel i, Account me, Village t, int scouts, double skill, Instant now) {
        if (!d.scouts() || scouts < MIN_SCOUTS) return 0;
        if (i != null && i.getScoutPausedUntil() != null) {
            if (now.isBefore(i.getScoutPausedUntil())) return 0;
            intel.resumeScouting(me.getId(), t.getId()); // the pause is over: failed runs are counted afresh
            i.setFailedScouts(0);
            i.setScoutPausedUntil(null);
        }
        int expected = i != null && i.getTroops() != null ? NpcIntelService.parse(i.getTroops()).getOrDefault(UnitType.SCOUT, 0) : 8;
        int want = Math.max((int) Math.round(4 + 6 * skill), (int) Math.ceil(1.5 * expected) + 3);
        if (i != null && i.getFailedScouts() > 0) {
            want = Math.max(want, 2 * i.getScoutsSent());
            return scouts >= want ? want : 0;
        }
        return Math.min(scouts, want);
    }

    // A chain of attacks, each with one nobleman and an escort that wins alone: they land one after the other and take the loyalty down.
    private void sendConquest(Village v, Account me, Conquest c, WorldView view) {
        Village t = c.c().target();
        int sent = 0;
        for (int k = 0; k < c.waves().count(); k++) {
            Map<UnitType, Integer> send = new EnumMap<>(c.waves().escortEach());
            send.put(UnitType.SNOB, 1);
            try {
                Movement m = movementService.sendAttack(v, t, send);
                view.added(m);
                if (sent == 0 && t.getOwner() != null && !t.getOwner().isNpc()) pace.record(t.getOwner().getId(), m.getArrivesAt()); // a train counts once
                sent++;
            } catch (MovementService.MovementException e) {
                break; // out of noblemen range or protected: the chain is not worth starting half way
            }
        }
        if (sent > 0) {
            npcLog.add(v.getWorld(), me, v, "NOBLE", "Sent " + sent + " noblemen in " + sent + " waves from " + v.getName() + " to take " + t.getName()
                    + " (" + t.getX() + "|" + t.getY() + ")" + (t.getOwner() == null ? "" : " of " + t.getOwner().getUsername()));
        }
    }

    private void sendStrike(Village v, Account me, Strike s, WorldView view) {
        Candidate c = s.c();
        Map<UnitType, Integer> send = new EnumMap<>(s.plan().send());
        try {
            Movement m = movementService.sendAttack(v, c.target(), send);
            view.added(m);
            if (c.target().getOwner() != null && !c.target().getOwner().isNpc()) pace.record(c.target().getOwner().getId(), m.getArrivesAt());
            int units = send.values().stream().mapToInt(Integer::intValue).sum();
            Village t = c.target();
            npcLog.add(v.getWorld(), me, v, "RAID", "Sent " + units + " units" + (send.containsKey(UnitType.SNOB) ? " with noblemen" : "")
                    + " from " + v.getName() + " at " + t.getName() + " (" + t.getX() + "|" + t.getY() + ")"
                    + (t.getOwner() == null ? "" : " of " + t.getOwner().getUsername()) + " [" + s.basis()
                    + ", loss " + Math.round(s.plan().lossShare() * 100) + " %]");
        } catch (MovementService.MovementException ignored) {
            // e.g. the nobles are out of range of this target: try again next time
        }
    }

    private void sendScouts(Village v, Account me, Village target, int n, WorldView view) {
        Map<UnitType, Integer> send = new EnumMap<>(UnitType.class);
        send.put(UnitType.SCOUT, n);
        try {
            Movement m = movementService.sendAttack(v, target, send);
            view.added(m);
            npcLog.add(v.getWorld(), me, v, "SCOUT", "Sent " + n + " scouts from " + v.getName() + " to " + target.getName()
                    + " (" + target.getX() + "|" + target.getY() + ")" + (target.getOwner() == null ? "" : " of " + target.getOwner().getUsername()));
        } catch (MovementService.MovementException ignored) {
            // out of range or protected: next time
        }
    }

    private static Strike weighted(List<Strike> strikes, Random rnd) {
        return strikes.get(pickIndex(strikes.stream().map(Strike::score).toList(), rnd));
    }

    private static int pickIndex(List<Double> weights, Random rnd) {
        double sum = 0;
        for (double w : weights) sum += w;
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < weights.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) return i;
        }
        return weights.size() - 1;
    }
}
