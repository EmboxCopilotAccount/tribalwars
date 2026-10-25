package se.oscarwiklund.twlan2.backend.service;

import se.oscarwiklund.twlan2.backend.domain.*;
import se.oscarwiklund.twlan2.backend.live.LiveUpdates;
import se.oscarwiklund.twlan2.backend.repo.CombatReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Reports that aren't battles: support arriving, sent back or withdrawn, and merchants delivering. They share the
// combat_report table (detail "kind" tells them apart; no kind = a battle), so the report table never changes:
// the "from" village is the attacker side (att_* detail, attackerVillageName/Player), the "to" village the defender
// side, units are "att:<UNIT>" and delivered resources the loot fields. Like battle reports, only real players
// get them: one per player involved (one in all when both villages are the same player's), the receiving side's
// copy marked defenderView.
@Service
public class ReportService {

    public static final int SUPPORT_ARRIVED = 1;
    public static final int SUPPORT_SENT_BACK = 2;  // the host sent the support home
    public static final int SUPPORT_WITHDRAWN = 3;  // the supporter called it home
    public static final int TRADE_DELIVERED = 10;

    private final CombatReportRepository reports;
    private final LiveUpdates live;

    public ReportService(CombatReportRepository reports, LiveUpdates live) {
        this.reports = reports;
        this.live = live;
    }

    // "BATTLE", "SUPPORT" or "TRADE" for a report's detail
    public static String category(Map<String, Integer> detail) {
        Integer kind = detail == null ? null : detail.get("kind");
        if (kind == null) return "BATTLE";
        return kind >= TRADE_DELIVERED ? "TRADE" : "SUPPORT";
    }

    public static String event(Map<String, Integer> detail) {
        Integer kind = detail == null ? null : detail.get("kind");
        if (kind == null) return null;
        return switch (kind) {
            case SUPPORT_ARRIVED -> "SUPPORT_ARRIVED";
            case SUPPORT_SENT_BACK -> "SUPPORT_SENT_BACK";
            case SUPPORT_WITHDRAWN -> "SUPPORT_WITHDRAWN";
            case TRADE_DELIVERED -> "TRADE_DELIVERED";
            default -> null;
        };
    }

    // `from` sent the troops, `to` is where they are (were) stationed.
    @Transactional
    public void support(int kind, Village from, Village to, Map<UnitType, Integer> units) {
        Map<String, Integer> detail = new LinkedHashMap<>();
        units.forEach((t, n) -> { if (n != null && n > 0) detail.put("att:" + t.name(), n); });
        write(kind, from, to, detail, 0, 0, 0);
    }

    // Merchants of `from` reached `to` with `out`; `back` is what they carry home (an accepted market offer).
    @Transactional
    public void trade(Village from, Village to, int outWood, int outClay, int outIron, int backWood, int backClay, int backIron) {
        Map<String, Integer> detail = new LinkedHashMap<>();
        if (backWood > 0) detail.put("back_wood", backWood);
        if (backClay > 0) detail.put("back_clay", backClay);
        if (backIron > 0) detail.put("back_iron", backIron);
        write(TRADE_DELIVERED, from, to, detail, outWood, outClay, outIron);
    }

    private void write(int kind, Village from, Village to, Map<String, Integer> detail, double wood, double clay, double iron) {
        if (from == null || to == null || from.getWorld() == null) return;
        detail.put("kind", kind);
        detail.put("att_village", from.getId().intValue());
        detail.put("def_village", to.getId().intValue());
        detail.put("att_x", from.getX());
        detail.put("att_y", from.getY());
        detail.put("def_x", to.getX());
        detail.put("def_y", to.getY());
        Account sender = from.getOwner();
        Account receiver = to.getOwner();
        Long worldId = from.getWorld().getId();
        Instant now = Instant.now();
        boolean same = sender != null && receiver != null && sender.getId().equals(receiver.getId());
        if (sender != null && !sender.isNpc()) save(sender, false, from, to, detail, wood, clay, iron, now, worldId);
        if (receiver != null && !receiver.isNpc() && !same) save(receiver, true, from, to, detail, wood, clay, iron, now, worldId);
    }

    private void save(Account owner, boolean receiving, Village from, Village to, Map<String, Integer> detail,
                      double wood, double clay, double iron, Instant now, Long worldId) {
        CombatReport r = new CombatReport();
        r.setWorldId(worldId);
        r.setAccountId(owner.getId());
        r.setAttackerVillageName(from.getName());
        r.setDefenderVillageName(to.getName());
        r.setAttackerPlayer(from.getOwner() == null ? null : from.getOwner().getUsername());
        r.setDefenderPlayer(to.getOwner() == null ? null : to.getOwner().getUsername());
        r.setDefenderView(receiving);
        r.setLootWood(wood);
        r.setLootClay(clay);
        r.setLootIron(iron);
        r.setOccurredAt(now);
        r.setConquered(false);
        r.setDetail(new LinkedHashMap<>(detail));
        reports.save(r);
        live.toAccount(owner, worldId, LiveUpdates.REPORTS);
    }
}
