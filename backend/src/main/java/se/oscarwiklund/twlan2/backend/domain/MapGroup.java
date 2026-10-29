package se.oscarwiklund.twlan2.backend.domain;

import jakarta.persistence.*;

// Map > Manage groups > Other villages: a player's named, coloured set of players, tribes and villages that the map
// paints in that colour (#119). The entries are MapGroupEntry rows.
@Entity
@Table(name = "map_group")
public class MapGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long accountId;
    private String name;
    private String color; // "#rrggbb"
    private boolean shown = true;

    public Long getId() { return id; }
    public Long getWorldId() { return worldId; }
    public void setWorldId(Long worldId) { this.worldId = worldId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public boolean isShown() { return shown; }
    public void setShown(boolean shown) { this.shown = shown; }
}
