package se.oscarwiklund.twlan2.backend.web;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.PlayerWarService;
import se.oscarwiklund.twlan2.backend.service.ProfileService;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProfileController {

    private final ProfileService profiles;
    private final GameFacade gameFacade;
    private final se.oscarwiklund.twlan2.backend.service.StatsService stats;
    private final PlayerWarService wars;

    public ProfileController(ProfileService profiles, GameFacade gameFacade, se.oscarwiklund.twlan2.backend.service.StatsService stats, PlayerWarService wars) {
        this.stats = stats;
        this.wars = wars;
        this.profiles = profiles;
        this.gameFacade = gameFacade;
    }

    private Account me() {
        Account a = AccountContext.get();
        if (a == null) throw new AuthService.AuthException("Please log in.");
        return a;
    }

    // "me" is the logged-in player.
    @GetMapping("/api/players/{name}")
    public ProfileService.Profile profile(@PathVariable String name) {
        Account viewer = me();
        return profiles.profile("me".equals(name) ? viewer.getUsername() : name, viewer, gameFacade.currentWorld());
    }

    // War on an NPC (#118): declared here or by attacking it; peace only after the world's warPeaceHours (409 before).
    // Both answer with the NPC's refreshed profile.
    @PostMapping("/api/players/{name}/war")
    public ProfileService.Profile declareWar(@PathVariable String name) {
        Account viewer = me();
        var world = gameFacade.currentWorld();
        wars.declare(viewer, name, world);
        return profiles.profile(name, viewer, world);
    }

    @DeleteMapping("/api/players/{name}/war")
    public ProfileService.Profile makePeace(@PathVariable String name) {
        Account viewer = me();
        var world = gameFacade.currentWorld();
        wars.makePeace(viewer, name, world);
        return profiles.profile(name, viewer, world);
    }

    @PutMapping("/api/profile")
    public ProfileService.Profile edit(@RequestBody ProfileService.Edit edit) {
        profiles.edit(me(), edit);
        return profiles.profile(me().getUsername(), me(), gameFacade.currentWorld());
    }

    // range = hour | day | week | all.
    @GetMapping("/api/players/{name}/stats")
    public se.oscarwiklund.twlan2.backend.service.StatsService.Stats stats(@PathVariable String name, @RequestParam(defaultValue = "day") String range) {
        Account viewer = me();
        java.time.Instant from = switch (range) {
            case "hour" -> java.time.Instant.now().minus(java.time.Duration.ofHours(1));
            case "week" -> java.time.Instant.now().minus(java.time.Duration.ofDays(7));
            case "all" -> java.time.Instant.EPOCH;
            default -> java.time.Instant.now().minus(java.time.Duration.ofDays(1));
        };
        return stats.stats("me".equals(name) ? viewer.getUsername() : name, gameFacade.currentWorld(), from);
    }
}
