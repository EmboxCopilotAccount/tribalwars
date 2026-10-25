/* A battle report as BB code for the forum, tribe mail or messages: the same facts as the report screen (sides, troops
   and losses, luck, loot, damage, loyalty, scouting), written with the tags lib/bbcode.jsx renders. */
import { UNIT_LIST } from "./military";
import { buildingTypeName } from "./buildings";

// "Name (500|500) K55" -> "Name [coord]500|500[/coord] K55"
const village = (label) => String(label ?? "?").replace(/\((\d+)\|(\d+)\)/, "[coord]$1|$2[/coord]");
const player = (name) => (name ? `[player]${name}[/player]` : "---");

// [table] with a unit-icon head row and one row per {title: {unitId: n}}, only the units present in any row
function unitTable(rows) {
  const ids = UNIT_LIST.map((u) => u.id).filter((id) => Object.values(rows).some((r) => (r[id] ?? 0) > 0));
  if (!ids.length) return "none";
  const head = `[**][||]${ids.map((id) => `[unit]${id}[/unit]`).join("[||]")}[/**]`;
  const body = Object.entries(rows).map(([title, r]) => `[*]${title}[|]${ids.map((id) => r[id] ?? 0).join("[|]")}`);
  return `[table]${head}${body.join("")}[/table]`;
}

/**
 * @param r the report
 * @param o the screen's derived values: subject, winner, attacker/defender names and labels, before/losses maps of
 *          both sides, showDefenderTroops, luck, loot {wood, clay, iron, total, max} (null when nothing was looted),
 *          ids: unit type map ({SPEAR: n}) -> unit id map ({spear: n}) for the scouted troops
 */
export function reportBBCode(r, o) {
  const out = [`[b]${o.subject}[/b]`, `Time: ${o.time}`, `[b]${o.winner} has won[/b]`, ""];
  const luck = `Attacker's luck: ${o.luck > 0 ? "+" : ""}${o.luck}%`;
  out.push([luck, r.morale != null ? `Morale: ${r.morale}%` : null, r.nightBonus > 100 ? `Night bonus: defence x${r.nightBonus / 100}` : null]
    .filter(Boolean).join("  "));
  out.push("", `[b]Attacker:[/b] ${player(o.attacker)}`, `Origin: ${village(o.fromLabel)}`);
  out.push(unitTable({ "Quantity:": o.beforeA, "Losses:": o.lossesA }));
  out.push("", `[b]Defender:[/b] ${player(o.defender)}`, `Target: ${village(o.toLabel)}`);
  out.push(o.showDefenderTroops ? unitTable({ "Quantity:": o.beforeD, "Losses:": o.lossesD })
    : "No information about the strength of the enemy's army could be collected.");
  const results = [];
  if (o.loot) results.push(`Loot: ${o.loot.wood} wood, ${o.loot.clay} clay, ${o.loot.iron} iron (${o.loot.total}/${o.loot.max})`);
  if (r.wallBefore != null && r.wallAfter != null && r.wallAfter < r.wallBefore)
    results.push(`Wall damaged from level ${r.wallBefore} to ${r.wallAfter}`);
  for (const [type, levels] of Object.entries(r.buildingDamage ?? {}))
    if (type !== "WALL") results.push(`${buildingTypeName(type)} was damaged by ${levels} ${levels === 1 ? "level" : "levels"}`);
  if (r.loyaltyFrom != null && r.loyaltyTo != null)
    results.push(`Loyalty loss from ${Math.ceil(r.loyaltyFrom)} to ${Math.ceil(r.loyaltyTo)}${r.conquered ? ". The village has been conquered." : ""}`);
  if (results.length) out.push("", ...results);
  const spy = r.spy;
  if (spy) {
    out.push("", "[b]Espionage[/b]", `Resources scouted: ${spy.wood ?? 0} wood, ${spy.clay ?? 0} clay, ${spy.iron ?? 0} iron`);
    if (spy.level >= 2 && spy.buildings)
      out.push(`Buildings: ${Object.entries(spy.buildings).map(([t, l]) => `${buildingTypeName(t)} ${l}`).join(", ")}`);
    if (spy.level >= 3) out.push("Troops in the village:", unitTable({ "Quantity:": o.ids(spy.unitsHome) }));
    if (spy.level >= 4) out.push("Troops outside:", unitTable({ "Quantity:": o.ids(spy.unitsAway) }));
  }
  return out.join("\n");
}
