package se.oscarwiklund.twlan2.backend.domain;

import jakarta.persistence.*;

// Village overview > Notes (#104): a player's own note on one of their villages. Kept per account, so a new owner
// of a conquered village never sees the old owner's note.
@Entity
@Table(name = "village_note", uniqueConstraints = @UniqueConstraint(columnNames = {"villageId", "accountId"}))
public class VillageNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long villageId;
    private Long accountId;
    @Column(length = 4000)
    private String text;

    public Long getId() { return id; }
    public Long getWorldId() { return worldId; }
    public void setWorldId(Long worldId) { this.worldId = worldId; }
    public Long getVillageId() { return villageId; }
    public void setVillageId(Long villageId) { this.villageId = villageId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
