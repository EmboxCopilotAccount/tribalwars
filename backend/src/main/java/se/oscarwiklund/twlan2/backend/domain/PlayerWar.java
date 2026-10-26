package se.oscarwiklund.twlan2.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;

// A real player at war with an NPC (and through it with the NPC members of its tribe): only NPCs at war with a player
// attack that player. Started with the profile's "Declare war" (declaredBy PLAYER) or by the player's first attack on
// the NPC (ATTACK, pending until an attack lands: cancelling it in time calls the war off). Raw ids, no foreign keys
// (cleaned by PlayerWarService).
@Entity
@Table(name = "player_war", indexes = {@Index(columnList = "worldId"), @Index(columnList = "npcAccountId")},
        uniqueConstraints = @UniqueConstraint(columnNames = {"worldId", "playerAccountId", "npcAccountId"}))
public class PlayerWar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long playerAccountId;
    private Long npcAccountId;
    // PLAYER or ATTACK
    private String declaredBy;
    private Instant startedAt;
    // true while the war was started only by attacks that have not landed yet
    private boolean pending;

    public Long getId() { return id; }
    public Long getWorldId() { return worldId; }
    public void setWorldId(Long worldId) { this.worldId = worldId; }
    public Long getPlayerAccountId() { return playerAccountId; }
    public void setPlayerAccountId(Long playerAccountId) { this.playerAccountId = playerAccountId; }
    public Long getNpcAccountId() { return npcAccountId; }
    public void setNpcAccountId(Long npcAccountId) { this.npcAccountId = npcAccountId; }
    public String getDeclaredBy() { return declaredBy; }
    public void setDeclaredBy(String declaredBy) { this.declaredBy = declaredBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }
}
