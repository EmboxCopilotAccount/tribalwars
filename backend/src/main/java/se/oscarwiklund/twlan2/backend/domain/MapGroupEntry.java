package se.oscarwiklund.twlan2.backend.domain;

import jakarta.persistence.*;

// One member of a MapGroup: targetId is an account id (PLAYER), tribe id (TRIBE) or village id (VILLAGE).
@Entity
@Table(name = "map_group_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"groupId", "kind", "targetId"}))
public class MapGroupEntry {

    public enum Kind { PLAYER, TRIBE, VILLAGE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long groupId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private Long targetId;

    public Long getId() { return id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
}
