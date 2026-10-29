package se.oscarwiklund.twlan2.backend.web;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.service.AuthService;
import se.oscarwiklund.twlan2.backend.service.VillageNoteService;
import org.springframework.web.bind.annotation.*;

// The current village's note (#104).
@RestController
public class VillageNoteController {

    private final VillageNoteService notes;
    private final GameFacade gameFacade;

    public VillageNoteController(VillageNoteService notes, GameFacade gameFacade) {
        this.notes = notes;
        this.gameFacade = gameFacade;
    }

    private Account me() {
        Account a = AccountContext.get();
        if (a == null) throw new AuthService.AuthException("Please log in.");
        return a;
    }

    public record Note(String text) {}

    @GetMapping("/api/village/note")
    public Note get() { return new Note(notes.get(me(), gameFacade.playerHomeVillage())); }

    @PutMapping("/api/village/note")
    public Note save(@RequestBody Note request) { return new Note(notes.save(me(), gameFacade.playerHomeVillage(), request.text())); }
}
