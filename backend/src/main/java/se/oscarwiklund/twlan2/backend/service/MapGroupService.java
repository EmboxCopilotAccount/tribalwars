package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Map groups (#119, Map > Manage groups > Other villages): a player's own named, coloured sets of players, tribes and
// villages; the map paints their villages in the group's colour. Rule breaks throw IllegalArgumentException (shown to
// the player, HTTP 400). Entries whose player, tribe or village is gone are left out of the list.
@Service
public class MapGroupService {

    public static final int MAX_NAME = 30;
    public static final int MAX_GROUPS = 50;
    public static final int MAX_ENTRIES = 100;
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern COORDS = Pattern.compile("\\(?\\s*(\\d{1,3})\\s*[|,]\\s*(\\d{1,3})\\s*\\)?");

    // label: player name, "[TAG] tribe name" or "village name (x|y)"; name: the player's name for PLAYER (the map
    // matches villages by owner name), else null
    public record Entry(Long id, MapGroupEntry.Kind kind, Long targetId, String label, String name) {}
    public record Group(Long id, String name, String color, boolean shown, List<Entry> entries) {}

    private final MapGroupRepository groups;
    private final MapGroupEntryRepository entries;
    private final AccountRepository accounts;
    private final TribeRepository tribes;
    private final VillageRepository villages;

    public MapGroupService(MapGroupRepository groups, MapGroupEntryRepository entries, AccountRepository accounts,
                           TribeRepository tribes, VillageRepository villages) {
        this.groups = groups;
        this.entries = entries;
        this.accounts = accounts;
        this.tribes = tribes;
        this.villages = villages;
    }

    // Oldest first, entries in the order they were added.
    @Transactional(readOnly = true)
    public List<Group> list(Account account, World world) {
        List<MapGroup> mine = groups.findByWorldIdAndAccountIdOrderByIdAsc(world.getId(), account.getId());
        if (mine.isEmpty()) return List.of();
        Map<Long, List<Entry>> byGroup = new HashMap<>();
        List<MapGroupEntry> all = new ArrayList<>(entries.findByGroupIdIn(mine.stream().map(MapGroup::getId).toList()));
        all.sort(Comparator.comparing(MapGroupEntry::getId));
        for (MapGroupEntry e : all) {
            Entry resolved = resolve(e, world);
            if (resolved != null) byGroup.computeIfAbsent(e.getGroupId(), k -> new ArrayList<>()).add(resolved);
        }
        List<Group> out = new ArrayList<>();
        for (MapGroup g : mine) out.add(new Group(g.getId(), g.getName(), g.getColor(), g.isShown(), byGroup.getOrDefault(g.getId(), List.of())));
        return out;
    }

    private Entry resolve(MapGroupEntry e, World world) {
        switch (e.getKind()) {
            case PLAYER -> {
                return accounts.findById(e.getTargetId())
                        .map(a -> new Entry(e.getId(), e.getKind(), a.getId(), a.getUsername(), a.getUsername())).orElse(null);
            }
            case TRIBE -> {
                return tribes.findById(e.getTargetId()).filter(t -> world.getId().equals(t.getWorldId()))
                        .map(t -> new Entry(e.getId(), e.getKind(), t.getId(), "[" + t.getTag() + "] " + t.getName(), null)).orElse(null);
            }
            default -> {
                return villages.findById(e.getTargetId()).filter(v -> v.getWorld() != null && v.getWorld().getId().equals(world.getId()))
                        .map(v -> new Entry(e.getId(), e.getKind(), v.getId(), v.getName() + " (" + v.getX() + "|" + v.getY() + ")", null)).orElse(null);
            }
        }
    }

    static String cleanColor(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (!COLOR.matcher(c).matches()) throw new IllegalArgumentException("Please choose a colour");
        return c.toLowerCase();
    }

    private String cleanName(String raw, Account account, World world, Long ownId) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Please enter a group name");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("The group name may have at most " + MAX_NAME + " characters");
        for (MapGroup g : groups.findByWorldIdAndAccountIdOrderByIdAsc(world.getId(), account.getId())) {
            if (!g.getId().equals(ownId) && g.getName().equalsIgnoreCase(name)) throw new IllegalArgumentException("A group with this name already exists");
        }
        return name;
    }

    private MapGroup own(Long id, Account account, World world) {
        return groups.findById(id)
                .filter(g -> g.getAccountId().equals(account.getId()) && g.getWorldId().equals(world.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
    }

    @Transactional
    public List<Group> create(Account account, World world, String name, String color) {
        String clean = cleanName(name, account, world, null);
        if (groups.findByWorldIdAndAccountIdOrderByIdAsc(world.getId(), account.getId()).size() >= MAX_GROUPS) {
            throw new IllegalArgumentException("You cannot have more than " + MAX_GROUPS + " groups");
        }
        MapGroup g = new MapGroup();
        g.setWorldId(world.getId());
        g.setAccountId(account.getId());
        g.setName(clean);
        g.setColor(cleanColor(color));
        groups.save(g);
        return list(account, world);
    }

    // null fields stay as they are
    @Transactional
    public List<Group> update(Account account, World world, Long id, String name, String color, Boolean shown) {
        MapGroup g = own(id, account, world);
        if (name != null) g.setName(cleanName(name, account, world, g.getId()));
        if (color != null) g.setColor(cleanColor(color));
        if (shown != null) g.setShown(shown);
        groups.save(g);
        return list(account, world);
    }

    @Transactional
    public List<Group> delete(Account account, World world, Long id) {
        MapGroup g = own(id, account, world);
        entries.deleteByGroupId(g.getId());
        groups.delete(g);
        return list(account, world);
    }

    // query: a player's name, a tribe's tag or name, or a village's coordinates ("500|500")
    @Transactional
    public List<Group> addEntry(Account account, World world, Long groupId, MapGroupEntry.Kind kind, String query) {
        MapGroup g = own(groupId, account, world);
        if (kind == null) throw new IllegalArgumentException("Please choose player, tribe or village");
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) throw new IllegalArgumentException("Please enter a name");
        Long target = switch (kind) {
            case PLAYER -> {
                Account a = accounts.findByUsernameLower(q.toLowerCase())
                        .filter(p -> !villages.findByWorldAndOwner(world, p).isEmpty())
                        .orElseThrow(() -> new IllegalArgumentException("There is no player with this name in this world"));
                if (a.getId().equals(account.getId())) throw new IllegalArgumentException("Your own villages always have their own colour");
                yield a.getId();
            }
            case TRIBE -> tribes.findByWorldIdAndTagLower(world.getId(), q.toLowerCase())
                    .or(() -> tribes.findByWorldIdAndNameLower(world.getId(), q.toLowerCase()))
                    .orElseThrow(() -> new IllegalArgumentException("There is no tribe with this tag or name"))
                    .getId();
            case VILLAGE -> {
                Matcher m = COORDS.matcher(q);
                if (!m.matches()) throw new IllegalArgumentException("Please enter the village's coordinates, e.g. 500|500");
                yield villages.findFirstByWorldAndXAndY(world, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                        .orElseThrow(() -> new IllegalArgumentException("There is no village at these coordinates"))
                        .getId();
            }
        };
        List<MapGroupEntry> here = entries.findByGroupId(g.getId());
        if (here.stream().anyMatch(e -> e.getKind() == kind && e.getTargetId().equals(target))) {
            throw new IllegalArgumentException("This is already in the group");
        }
        if (here.size() >= MAX_ENTRIES) throw new IllegalArgumentException("A group may have at most " + MAX_ENTRIES + " entries");
        MapGroupEntry e = new MapGroupEntry();
        e.setGroupId(g.getId());
        e.setKind(kind);
        e.setTargetId(target);
        entries.save(e);
        return list(account, world);
    }

    @Transactional
    public List<Group> removeEntry(Account account, World world, Long groupId, Long entryId) {
        MapGroup g = own(groupId, account, world);
        MapGroupEntry e = entries.findById(entryId).filter(x -> x.getGroupId().equals(g.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
        entries.delete(e);
        return list(account, world);
    }

    // ---- cleanup hooks ---------------------------------------------------------------------------------------------

    @Transactional
    public void deleteAccountData(Long accountId) {
        for (MapGroup g : groups.findByAccountId(accountId)) {
            entries.deleteByGroupId(g.getId());
            groups.delete(g);
        }
    }

    @Transactional
    public void deleteWorldData(Long worldId) {
        for (MapGroup g : groups.findByWorldId(worldId)) {
            entries.deleteByGroupId(g.getId());
            groups.delete(g);
        }
    }
}
