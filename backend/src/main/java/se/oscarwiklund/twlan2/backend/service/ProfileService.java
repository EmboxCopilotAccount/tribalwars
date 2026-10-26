package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ProfileService {

    public record VillageRow(Long id, String name, int x, int y, int points) {}
    public record AchievementRow(String key, String name, String icon, int level, int maxLevel) {}
    // A war with an NPC (#118). opponent: the NPC; declaredOn: the NPC it was declared on (a tribe-mate of the opponent when
    // viaTribe); declaredBy: PLAYER (the profile button) or ATTACK (the player's attack started it); pending: no attack of
    // it has landed yet; tribeId/tribeName: the opponent's tribe.
    public record WarInfo(String opponent, String declaredOn, boolean viaTribe, String declaredBy, Instant startedAt, Instant peaceFrom,
                          boolean pending, Long tribeId, String tribeName) {}
    // war: the viewer's war with this NPC (null: none); canDeclareWar: the viewer may declare war on it; wars: the
    // viewer's own wars (own profile only); npcsAttack: the world's NPC difficulty lets NPCs attack at all.
    public record Profile(String name, boolean npc, boolean own, boolean admin, int rank, int players, int points, List<VillageRow> villages,
                          long kills, Instant memberSince, String description, String location, String birthday,
                          List<AchievementRow> achievements, Long tribeId, String tribeName, String tribeTag, String tribeTitle,
                          WarInfo war, boolean canDeclareWar, List<WarInfo> wars, boolean npcsAttack, double warPeaceHours) {}

    private final WorldPoints worldPoints;
    private final AccountRepository accounts;
    private final VillageRepository villages;
    private final BuildingRepository buildings;
    private final PlayerProfileRepository profiles;
    private final AchievementCounterRepository counters;
    private final AchievementService achievements;
    private final TribeService tribes;
    private final TribeRepository tribeRepository;
    private final TribeMemberRepository tribeMembers;
    private final PlayerWarService playerWars;

    public ProfileService(WorldPoints worldPoints, TribeService tribes, TribeRepository tribeRepository, TribeMemberRepository tribeMembers, AccountRepository accounts, VillageRepository villages, BuildingRepository buildings,
                          PlayerProfileRepository profiles, AchievementCounterRepository counters, AchievementService achievements, PlayerWarService playerWars) {
        this.worldPoints = worldPoints;
        this.playerWars = playerWars;
        this.tribes = tribes;
        this.tribeRepository = tribeRepository;
        this.tribeMembers = tribeMembers;
        this.accounts = accounts;
        this.villages = villages;
        this.buildings = buildings;
        this.profiles = profiles;
        this.counters = counters;
        this.achievements = achievements;
    }

    @Transactional(readOnly = true)
    public Profile profile(String name, Account viewer, World world) {
        Account account = accounts.findByUsernameLower(name == null ? "" : name.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Player not found"));
        Map<Long, Integer> villagePoints = worldPoints.byVillage(world);
        Map<Long, Integer> totals = new HashMap<>();
        List<VillageRow> mine = new ArrayList<>();
        for (Village v : villages.findByWorld(world)) {
            if (v.getOwner() == null || v.getOwnerType() != OwnerType.PLAYER) continue;
            int p = villagePoints.getOrDefault(v.getId(), 0);
            totals.merge(v.getOwner().getId(), p, Integer::sum);
            if (v.getOwner().getId().equals(account.getId())) mine.add(new VillageRow(v.getId(), v.getName(), v.getX(), v.getY(), p));
        }
        if (mine.isEmpty()) throw new IllegalArgumentException("Player not found");
        mine.sort(Comparator.comparing(VillageRow::name));
        int points = totals.get(account.getId());
        int rank = 1 + (int) totals.values().stream().filter(p -> p > points).count();

        PlayerProfile p = profiles.findById(account.getId()).orElse(null);
        long kills = counters.findByAccountIdAndWorldIdAndCounterKey(account.getId(), world.getId(), "kills").map(AchievementCounter::getValue).orElse(0L);
        List<AchievementRow> earned = achievements.list(account, world).stream().filter(e -> e.level() > 0)
                .map(e -> new AchievementRow(e.key(), e.name(), e.key().equals("years_of_service") ? "years_of_service_" + Math.min(20, e.level()) : e.icon(), e.level(), e.maxLevel()))
                .toList();
        // the tribe and the player's title in it (titles are only shown to outsiders when the player allows it)
        TribeMember tm = tribeMembers.findByWorldIdAndAccountId(world.getId(), account.getId()).orElse(null);
        Tribe tribe = tm == null ? null : tribeRepository.findById(tm.getTribeId()).orElse(null);
        boolean sameTribe = tribe != null && viewer != null && tribes.sameTribe(viewer, account, world);
        String title = tm != null && (sameTribe || tm.isTitleOutside()) ? tm.getTitle() : "";
        boolean own = viewer != null && viewer.getId().equals(account.getId());

        // wars with NPCs: the viewer's war with this NPC, or (own profile) all the viewer's wars
        WarInfo war = null;
        boolean canDeclareWar = false;
        List<WarInfo> wars = List.of();
        if (viewer != null && !viewer.isNpc() && account.isNpc()) {
            PlayerWarService.Standing s = playerWars.standing(viewer, account, world);
            if (s != null) war = warInfo(s.war(), account, s.declaredOn(), tribe, world);
            canDeclareWar = s == null && !sameTribe && !villages.findByWorldAndOwner(world, viewer).isEmpty();
        }
        if (own && !account.isNpc()) {
            List<PlayerWar> rows = playerWars.warsOf(account, world);
            Map<Long, Account> npcs = new HashMap<>();
            for (Account a : accounts.findAllById(rows.stream().map(PlayerWar::getNpcAccountId).toList())) npcs.put(a.getId(), a);
            Map<Long, Tribe> tribeOf = rows.isEmpty() ? Map.of() : tribes.tribeByAccount(world);
            List<WarInfo> list = new ArrayList<>();
            for (PlayerWar w : rows) {
                Account npc = npcs.get(w.getNpcAccountId());
                if (npc != null) list.add(warInfo(w, npc, npc, tribeOf.get(npc.getId()), world));
            }
            wars = list;
        }
        return new Profile(account.getUsername(), account.isNpc(), own, account.isAdmin(),
                rank, totals.size(), points, mine, kills, account.getCreatedAt(),
                p == null ? "" : p.getDescription(), p == null ? "" : p.getLocation(), p == null ? "" : p.getBirthday(), earned,
                tribe == null ? null : tribe.getId(), tribe == null ? null : tribe.getName(), tribe == null ? null : tribe.getTag(), title,
                war, canDeclareWar, wars, se.oscarwiklund.twlan2.backend.service.npc.NpcDifficulty.of(world).attacks(),
                WorldSettings.number(world, "warPeaceHours"));
    }

    private static WarInfo warInfo(PlayerWar w, Account opponent, Account declaredOn, Tribe tribe, World world) {
        return new WarInfo(opponent.getUsername(), declaredOn.getUsername(), !declaredOn.getId().equals(opponent.getId()), w.getDeclaredBy(),
                w.getStartedAt(), PlayerWarService.peaceFrom(w, world), w.isPending(),
                tribe == null ? null : tribe.getId(), tribe == null ? null : tribe.getName());
    }

    public record Edit(String description, String location, String birthday) {}

    @Transactional
    public void edit(Account account, Edit edit) {
        String description = edit.description() == null ? "" : edit.description().trim();
        String location = edit.location() == null ? "" : edit.location().trim();
        String birthday = edit.birthday() == null ? "" : edit.birthday().trim();
        if (description.length() > 4000) throw new IllegalArgumentException("The description may have at most 4000 characters.");
        if (location.length() > 60) throw new IllegalArgumentException("The location may have at most 60 characters.");
        if (!birthday.isEmpty()) {
            try {
                if (LocalDate.parse(birthday).isAfter(LocalDate.now())) throw new IllegalArgumentException("The birthday cannot be in the future.");
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("The birthday must be a date like 1990-04-23.");
            }
        }
        PlayerProfile p = profiles.findById(account.getId()).orElseGet(() -> {
            PlayerProfile n = new PlayerProfile();
            n.setAccountId(account.getId());
            return n;
        });
        p.setDescription(description);
        p.setLocation(location);
        p.setBirthday(birthday);
        profiles.save(p);
    }
}
