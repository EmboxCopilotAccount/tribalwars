package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.AccountRepository;
import se.oscarwiklund.twlan2.backend.repo.MovementRepository;
import se.oscarwiklund.twlan2.backend.repo.PlayerWarRepository;
import se.oscarwiklund.twlan2.backend.repo.VillageRepository;
import se.oscarwiklund.twlan2.backend.service.npc.NpcLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

// Personal wars between real players and NPCs (#118): NPCs only scout and attack players they are at war with. A war
// is declared on one NPC and counts for every NPC member of its current tribe too. The player declares it on the NPC's
// profile, or by sending any attack (scouts included) at one of its villages; such a war is pending until an attack
// lands, and cancelling the last attack on its way in time calls it off. Only the player can make peace, and not before
// the world's warPeaceHours have passed.
@Service
public class PlayerWarService {

    public static final String BY_PLAYER = "PLAYER";
    public static final String BY_ATTACK = "ATTACK";

    // Peace was asked for before the cooldown ended (HTTP 409 with the time it becomes possible).
    public static class PeaceTooEarlyException extends RuntimeException {
        private final Instant peaceFrom;

        public PeaceTooEarlyException(String message, Instant peaceFrom) {
            super(message);
            this.peaceFrom = peaceFrom;
        }

        public Instant peaceFrom() { return peaceFrom; }
    }

    private final PlayerWarRepository wars;
    private final AccountRepository accounts;
    private final VillageRepository villages;
    private final MovementRepository movements;
    private final TribeService tribes;
    private final MailService mail;
    private final NpcLogService npcLog;

    public PlayerWarService(PlayerWarRepository wars, AccountRepository accounts, VillageRepository villages, MovementRepository movements,
                            TribeService tribes, MailService mail, NpcLogService npcLog) {
        this.wars = wars;
        this.accounts = accounts;
        this.villages = villages;
        this.movements = movements;
        this.tribes = tribes;
        this.mail = mail;
        this.npcLog = npcLog;
    }

    // When peace becomes possible: warPeaceHours (real hours) after the war began.
    public static Instant peaceFrom(PlayerWar war, World world) {
        double hours = Math.max(0, WorldSettings.number(world, "warPeaceHours"));
        return war.getStartedAt().plusMillis(Math.round(hours * 3_600_000));
    }

    // ---- who fights whom ---------------------------------------------------------------------------------------------

    // Every NPC at war in this world -> the players it is at war with (pending wars included). Read once per NPC tick.
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> enemiesByNpc(World world) {
        List<PlayerWar> rows = wars.findByWorldId(world.getId());
        if (rows.isEmpty()) return Map.of();
        Map<Long, Set<Long>> sides = sides(rows.stream().map(PlayerWar::getNpcAccountId).toList(), world);
        Map<Long, Set<Long>> out = new HashMap<>();
        for (PlayerWar w : rows) {
            for (Long npc : sides.get(w.getNpcAccountId())) out.computeIfAbsent(npc, k -> new HashSet<>()).add(w.getPlayerAccountId());
        }
        return out;
    }

    // The NPC itself and the NPC members of its current tribe, for each NPC given.
    private Map<Long, Set<Long>> sides(Collection<Long> npcIds, World world) {
        Map<Long, Tribe> tribeOf = tribes.tribeByAccount(world);
        Map<Long, List<Long>> membersOf = new HashMap<>();
        for (var e : tribeOf.entrySet()) membersOf.computeIfAbsent(e.getValue().getId(), k -> new ArrayList<>()).add(e.getKey());
        Set<Long> mates = new HashSet<>();
        for (Long npc : npcIds) {
            Tribe t = tribeOf.get(npc);
            if (t != null) mates.addAll(membersOf.get(t.getId()));
        }
        Set<Long> npcMates = new HashSet<>();
        for (Account a : accounts.findAllById(mates)) if (a.isNpc()) npcMates.add(a.getId());
        Map<Long, Set<Long>> out = new HashMap<>();
        for (Long npc : npcIds) {
            Set<Long> side = new HashSet<>();
            side.add(npc);
            Tribe t = tribeOf.get(npc);
            if (t != null) for (Long m : membersOf.get(t.getId())) if (npcMates.contains(m)) side.add(m);
            out.put(npc, side);
        }
        return out;
    }

    // The player's wars that make `npc` an enemy: the one declared on it first, then those declared on its tribe-mates.
    private List<PlayerWar> warsAgainst(Account player, Account npc, World world) {
        List<PlayerWar> mine = wars.findByWorldIdAndPlayerAccountId(world.getId(), player.getId());
        if (mine.isEmpty()) return List.of();
        Map<Long, Set<Long>> sides = sides(mine.stream().map(PlayerWar::getNpcAccountId).toList(), world);
        List<PlayerWar> out = new ArrayList<>();
        for (PlayerWar w : mine) if (w.getNpcAccountId().equals(npc.getId())) out.add(w);
        for (PlayerWar w : mine) if (!w.getNpcAccountId().equals(npc.getId()) && sides.get(w.getNpcAccountId()).contains(npc.getId())) out.add(w);
        return out;
    }

    // How a player stands with an NPC: the war that makes them enemies (null: peace) and the NPC it was declared on.
    public record Standing(PlayerWar war, Account declaredOn) {
        public boolean viaTribe(Account npc) { return !declaredOn.getId().equals(npc.getId()); }
    }

    @Transactional(readOnly = true)
    public Standing standing(Account player, Account npc, World world) {
        if (player == null || npc == null || player.isNpc() || !npc.isNpc()) return null;
        List<PlayerWar> against = warsAgainst(player, npc, world);
        if (against.isEmpty()) return null;
        PlayerWar w = against.get(0);
        Account on = w.getNpcAccountId().equals(npc.getId()) ? npc : accounts.findById(w.getNpcAccountId()).orElse(npc);
        return new Standing(w, on);
    }

    // The player's own wars in this world, oldest first.
    @Transactional(readOnly = true)
    public List<PlayerWar> warsOf(Account player, World world) {
        List<PlayerWar> list = new ArrayList<>(wars.findByWorldIdAndPlayerAccountId(world.getId(), player.getId()));
        list.sort(Comparator.comparing(PlayerWar::getStartedAt));
        return list;
    }

    // ---- declaring and ending ----------------------------------------------------------------------------------------

    private Account npcIn(World world, String name) {
        Account npc = accounts.findByUsernameLower(name == null ? "" : name.trim().toLowerCase()).orElse(null);
        if (npc == null || villages.findByWorldAndOwner(world, npc).isEmpty()) throw new IllegalArgumentException("Player not found");
        if (!npc.isNpc()) throw new IllegalArgumentException("You can only declare war on computer players.");
        return npc;
    }

    @Transactional
    public PlayerWar declare(Account player, String npcName, World world) {
        if (player == null || player.isNpc()) throw new IllegalArgumentException("Only players can declare war.");
        Account npc = npcIn(world, npcName);
        if (villages.findByWorldAndOwner(world, player).isEmpty()) throw new IllegalArgumentException("You have no village in this world.");
        if (tribes.sameTribe(player, npc, world)) throw new IllegalArgumentException(npc.getUsername() + " is in your tribe.");
        Standing s = standing(player, npc, world);
        if (s != null) {
            throw new IllegalArgumentException(s.viaTribe(npc) ? "You are already at war with " + npc.getUsername() + " through its tribe."
                    : "You are already at war with " + npc.getUsername() + ".");
        }
        PlayerWar w = start(world, player, npc, BY_PLAYER, false);
        npcLog.add(world, npc, null, "WAR", player.getUsername() + " declared war on " + npc.getUsername() + tribeNote(npc, world));
        return w;
    }

    @Transactional
    public void makePeace(Account player, String npcName, World world) {
        if (player == null || player.isNpc()) throw new IllegalArgumentException("Only players can make peace.");
        Account npc = npcIn(world, npcName);
        PlayerWar w = wars.findByWorldIdAndPlayerAccountIdAndNpcAccountId(world.getId(), player.getId(), npc.getId()).orElse(null);
        if (w == null) {
            Standing s = standing(player, npc, world);
            if (s != null) throw new IllegalArgumentException("You are at war with " + npc.getUsername() + " through its tribe: make peace with "
                    + s.declaredOn().getUsername() + ".");
            throw new IllegalArgumentException("You are not at war with " + npc.getUsername() + ".");
        }
        Instant from = peaceFrom(w, world);
        if (Instant.now().isBefore(from)) {
            throw new PeaceTooEarlyException("Peace with " + npc.getUsername() + " is not possible yet.", from);
        }
        wars.delete(w);
        npcLog.add(world, npc, null, "WAR", player.getUsername() + " made peace with " + npc.getUsername());
    }

    private PlayerWar start(World world, Account player, Account npc, String by, boolean pending) {
        PlayerWar w = new PlayerWar();
        w.setWorldId(world.getId());
        w.setPlayerAccountId(player.getId());
        w.setNpcAccountId(npc.getId());
        w.setDeclaredBy(by);
        w.setStartedAt(Instant.now());
        w.setPending(pending);
        return wars.save(w);
    }

    private String tribeNote(Account npc, World world) {
        return tribes.tribeOf(npc, world).map(t -> " (and its tribe " + t.getTag() + ")").orElse("");
    }

    // ---- wars started by attacks -------------------------------------------------------------------------------------

    // A player's attack at an NPC village was sent: it declares war, unless the two are at war already. Never throws
    // (it runs inside the attack's transaction).
    @Transactional
    public void onAttackSent(Movement m) {
        Village origin = m.getOriginVillage(), target = m.getTargetVillage();
        Account player = origin.getOwner(), npc = target.getOwner();
        World world = origin.getWorld();
        if (m.getType() != MovementType.ATTACK || m.isSelfAttack() || world == null) return;
        if (player == null || player.isNpc() || npc == null || !npc.isNpc()) return;
        if (tribes.sameTribe(player, npc, world) || standing(player, npc, world) != null) return;
        start(world, player, npc, BY_ATTACK, true);
        String tribe = tribes.tribeOf(npc, world).map(Tribe::getTag).orElse(null);
        npcLog.add(world, npc, target, "WAR", player.getUsername() + " attacked " + target.getName() + " (" + target.getX() + "|" + target.getY()
                + "): at war with " + npc.getUsername() + (tribe == null ? "" : " (and its tribe " + tribe + ")"));
        if (mail.accepts(player, world, npc)) {
            mail.send(npc, world, List.of(player.getUsername()), "War",
                    "You attacked my village " + target.getName() + " (" + target.getX() + "|" + target.getY() + "). From now on we are at war"
                            + (tribe == null ? "" : ", and so is my tribe " + tribe) + ".\n\nPeace is possible "
                            + hoursText(WorldSettings.number(world, "warPeaceHours")) + " after the war began, at the earliest.");
        }
    }

    private static String hoursText(double hours) {
        if (hours <= 0) return "right";
        String n = hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours);
        return n + (hours == 1 ? " hour" : " hours");
    }

    // A player's attack landed at an NPC village: a war it started is final now.
    @Transactional
    public void onAttackLanded(World world, Account attacker, Account npc) {
        if (world == null || attacker == null || attacker.isNpc() || npc == null || !npc.isNpc()) return;
        for (PlayerWar w : warsAgainst(attacker, npc, world)) {
            if (w.isPending()) {
                w.setPending(false);
                wars.save(w);
            }
        }
    }

    // A player called an attack at an NPC village off (the movement is already deleted): a war that only this attack
    // started is called off too, unless another attack at that NPC or its tribe-mates is still on its way.
    @Transactional
    public void onAttackCancelled(Movement m) {
        if (m.getType() != MovementType.ATTACK || m.isSelfAttack()) return;
        Account player = m.getOriginVillage().getOwner(), npc = m.getTargetVillage().getOwner();
        World world = m.getOriginVillage().getWorld();
        if (world == null || player == null || player.isNpc() || npc == null || !npc.isNpc()) return;
        for (PlayerWar w : warsAgainst(player, npc, world)) {
            if (!w.isPending() || !BY_ATTACK.equals(w.getDeclaredBy())) continue;
            Set<Long> side = sides(List.of(w.getNpcAccountId()), world).get(w.getNpcAccountId());
            if (movements.countFromOwnerTo(player.getId(), side, MovementType.ATTACK) > 0) continue;
            wars.delete(w);
            Account declaredOn = w.getNpcAccountId().equals(npc.getId()) ? npc : accounts.findById(w.getNpcAccountId()).orElse(null);
            if (declaredOn == null) continue;
            npcLog.add(world, declaredOn, null, "WAR", player.getUsername() + " called the attack off: no war with " + declaredOn.getUsername());
            if (mail.accepts(player, world, declaredOn)) {
                mail.send(declaredOn, world, List.of(player.getUsername()), "Stand down",
                        "Your troops turned back. We stand down: there is no war between us.");
            }
        }
    }

    // ---- cleanup -----------------------------------------------------------------------------------------------------

    @Transactional
    public void deleteAccountData(Long accountId) {
        wars.deleteByPlayerAccountId(accountId);
        wars.deleteByNpcAccountId(accountId);
    }

    // The account left the world (removed by an admin, or a retired NPC whose villages went barbarian).
    @Transactional
    public void leaveWorld(Long worldId, Long accountId) {
        wars.deleteByWorldIdAndPlayerAccountId(worldId, accountId);
        wars.deleteByWorldIdAndNpcAccountId(worldId, accountId);
    }

    @Transactional
    public void deleteWorldData(Long worldId) {
        wars.deleteByWorldId(worldId);
    }
}
