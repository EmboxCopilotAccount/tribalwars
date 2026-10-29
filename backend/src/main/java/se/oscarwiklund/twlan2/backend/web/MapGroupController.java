package se.oscarwiklund.twlan2.backend.web;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.MapGroupEntry;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.MapGroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Map groups (#119). Every call answers with the fresh list of groups.
@RestController
public class MapGroupController {

    private final MapGroupService groups;
    private final GameFacade gameFacade;

    public MapGroupController(MapGroupService groups, GameFacade gameFacade) {
        this.groups = groups;
        this.gameFacade = gameFacade;
    }

    private Account me() {
        Account a = AccountContext.get();
        if (a == null) throw new AuthService.AuthException("Please log in.");
        return a;
    }

    public record GroupRequest(String name, String color, Boolean shown) {}
    public record EntryRequest(MapGroupEntry.Kind kind, String query) {}

    @GetMapping("/api/map/groups")
    public List<MapGroupService.Group> list() { return groups.list(me(), gameFacade.currentWorld()); }

    @PostMapping("/api/map/groups")
    public List<MapGroupService.Group> create(@RequestBody GroupRequest r) { return groups.create(me(), gameFacade.currentWorld(), r.name(), r.color()); }

    @PutMapping("/api/map/groups/{id}")
    public List<MapGroupService.Group> update(@PathVariable Long id, @RequestBody GroupRequest r) {
        return groups.update(me(), gameFacade.currentWorld(), id, r.name(), r.color(), r.shown());
    }

    @DeleteMapping("/api/map/groups/{id}")
    public List<MapGroupService.Group> delete(@PathVariable Long id) { return groups.delete(me(), gameFacade.currentWorld(), id); }

    @PostMapping("/api/map/groups/{id}/entries")
    public List<MapGroupService.Group> addEntry(@PathVariable Long id, @RequestBody EntryRequest r) {
        return groups.addEntry(me(), gameFacade.currentWorld(), id, r.kind(), r.query());
    }

    @DeleteMapping("/api/map/groups/{id}/entries/{entryId}")
    public List<MapGroupService.Group> removeEntry(@PathVariable Long id, @PathVariable Long entryId) {
        return groups.removeEntry(me(), gameFacade.currentWorld(), id, entryId);
    }
}
