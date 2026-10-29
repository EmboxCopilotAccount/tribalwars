package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.Account;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.domain.VillageNote;
import se.oscarwiklund.twlan2.backend.repo.VillageNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Village notes (#104): plain text, one per village and owner; an empty text deletes the note.
@Service
public class VillageNoteService {

    public static final int MAX_LENGTH = 4000;

    private final VillageNoteRepository notes;

    public VillageNoteService(VillageNoteRepository notes) {
        this.notes = notes;
    }

    private static void checkOwner(Account account, Village village) {
        if (village.getOwner() == null || !village.getOwner().getId().equals(account.getId())) {
            throw new IllegalArgumentException("This is not your village");
        }
    }

    @Transactional(readOnly = true)
    public String get(Account account, Village village) {
        checkOwner(account, village);
        return notes.findByVillageIdAndAccountId(village.getId(), account.getId()).map(VillageNote::getText).orElse("");
    }

    @Transactional
    public String save(Account account, Village village, String raw) {
        checkOwner(account, village);
        String text = raw == null ? "" : raw.strip();
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("The note may have at most " + MAX_LENGTH + " characters");
        var existing = notes.findByVillageIdAndAccountId(village.getId(), account.getId());
        if (text.isEmpty()) {
            existing.ifPresent(notes::delete);
            return "";
        }
        VillageNote n = existing.orElseGet(VillageNote::new);
        n.setWorldId(village.getWorld() == null ? null : village.getWorld().getId());
        n.setVillageId(village.getId());
        n.setAccountId(account.getId());
        n.setText(text);
        notes.save(n);
        return text;
    }

    // ---- cleanup hooks ---------------------------------------------------------------------------------------------

    @Transactional
    public void deleteAccountData(Long accountId) { notes.deleteByAccountId(accountId); }

    @Transactional
    public void deleteWorldData(Long worldId) { notes.deleteByWorldId(worldId); }
}
