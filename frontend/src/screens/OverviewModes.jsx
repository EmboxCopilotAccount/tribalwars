// Overviews > Production, Troops, Commands, Incoming, Buildings, Research. The original renders "TBI" for all of them,
// so there are no templates to mirror: plain vis tables over GET /api/overview/villages (one row per own village).
import { useEffect, useState } from "react";
import { api } from "../api";
import { Timer } from "../lib/ui";
import { fmtOnTime, continentOf } from "../lib/military";
import { B, ORDER } from "../lib/buildings";
import { BonusIcon } from "../lib/BonusIcon";
import { COMBINED_UNITS } from "./OverviewVillagesScreen";
import { newTabClick, villageHref } from "../lib/nav";

const REFRESH_MS = 30000;

// the overview rows, fetched when the mode opens and every 30 s while it is open
function useVillageOverview() {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    let alive = true;
    const load = () =>
      api.getVillageOverview()
        .then((r) => alive && (setRows(r), setError(null)))
        .catch((e) => alive && setError(e?.message ?? "The overview could not be loaded."));
    load();
    const t = setInterval(load, REFRESH_MS);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);
  return { rows, error };
}

const label = (v) => `${v.name} (${v.x}|${v.y}) ${continentOf(v)}`;
const sum = (...maps) => {
  const out = {};
  for (const m of maps) for (const [k, n] of Object.entries(m ?? {})) out[k] = (out[k] ?? 0) + n;
  return out;
};
// the researchable units (spear, sword, paladin and nobleman need no research); a function, as COMBINED_UNITS comes
// from the screen that imports this module
const techUnits = () => COMBINED_UNITS.filter(([, type]) => !["SPEAR", "SWORD", "PALADIN", "SNOB"].includes(type));

// opens a village (village switch first when it is not the current one), then the screen
function useOpen(village, go, onSelectVillage) {
  return (id, screen) => (e) => {
    if (newTabClick(e)) return; // the href opens that village's screen in a new tab
    e.preventDefault();
    if (id !== village.id) onSelectVillage?.(id);
    go(null, screen);
  };
}

function VillageLink({ v, open, screen = "overview" }) {
  return (
    <>
      <a href={villageHref(v.id, screen)} onClick={open(v.id, screen)}>{label(v)}</a>
      <BonusIcon code={v.bonus} />
    </>
  );
}

function UnitHeads() {
  return COMBINED_UNITS.map(([id, , name]) => (
    <th key={id} style={{ textAlign: "center" }}>
      <img src={`/graphic/unit/unit_${id}.png`} alt={name} title={name} />
    </th>
  ));
}

function UnitCells({ units }) {
  return COMBINED_UNITS.map(([id, type]) => {
    const n = units?.[type] ?? 0;
    return (
      <td key={id} className={"unit-item" + (n ? "" : " hidden")}>
        {n}
      </td>
    );
  });
}

function Res({ k, n, title }) {
  return (
    <span className="nowrap" title={title}>
      <span className={`icon header ${k}`}> </span>
      {Math.floor(n)}{" "}
    </span>
  );
}

export function ProductionView({ rows, open }) {
  return (
    <table className="vis overview_table" width="100%">
      <tbody>
        <tr>
          <th>Village ({rows.length})</th>
          <th>Points</th>
          <th>Resources</th>
          <th>Warehouse</th>
          <th>Merchants</th>
          <th>Farm</th>
          <th>Construction</th>
          <th>Recruitment</th>
        </tr>
        {rows.map((v, i) => (
          <tr key={v.id} className={i % 2 ? "row_b" : "row_a"}>
            <td><VillageLink v={v} open={open} /></td>
            <td>{v.points}</td>
            <td className="nowrap">
              <Res k="wood" n={v.wood} title={`+${Math.round(v.woodPerHour)} per hour`} />
              <Res k="stone" n={v.clay} title={`+${Math.round(v.clayPerHour)} per hour`} />
              <Res k="iron" n={v.iron} title={`+${Math.round(v.ironPerHour)} per hour`} />
            </td>
            <td>{v.warehouseCapacity}</td>
            <td><a href={villageHref(v.id, "market")} onClick={open(v.id, "market")}>{`${v.merchantsAvailable}/${v.merchantsTotal}`}</a></td>
            <td><a href={villageHref(v.id, "farm")} onClick={open(v.id, "farm")}>{`${v.populationUsed}/${v.populationCapacity}`}</a></td>
            <td className="nowrap">
              {v.buildQueue.map((q, j) => {
                const id = ORDER.find((b) => B[b].type === q.type);
                const what = `${B[id]?.name ?? q.type} ${q.demolish ? "demolished to" : "to"} level ${q.targetLevel}`;
                return (
                  <a key={j} href={villageHref(v.id, "building:HEADQUARTERS")} onClick={open(v.id, "building:HEADQUARTERS")} title={`${what}, ${fmtOnTime(q.completesAt)}`}>
                    <img src={`/graphic/buildings/${id}.png`} alt={what} /> {q.targetLevel}{" "}
                  </a>
                );
              })}
            </td>
            <td className="nowrap">
              {COMBINED_UNITS.filter(([, type]) => v.recruiting[type] > 0).map(([id, type, name]) => (
                <span key={id} title={name}>
                  <img src={`/graphic/unit/unit_${id}.png`} alt={name} /> {v.recruiting[type]}{" "}
                </span>
              ))}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const TROOP_ROWS = [
  ["your own", (v) => v.home],
  ["in the village", (v) => sum(v.home, v.support)],
  ["outwards", (v) => v.away],
  ["in transit", (v) => v.moving],
  ["total", (v) => sum(v.home, v.away, v.moving)],
];

export function TroopsView({ rows, open }) {
  return (
    <table className="vis overview_table" width="100%">
      <tbody>
        <tr>
          <th>Village ({rows.length})</th>
          <th />
          <UnitHeads />
        </tr>
        {rows.flatMap((v, i) =>
          TROOP_ROWS.map(([what, units], j) => (
            <tr key={`${v.id}-${j}`} className={i % 2 ? "row_b" : "row_a"}>
              {j === 0 && (
                <td rowSpan={TROOP_ROWS.length} valign="top">
                  <VillageLink v={v} open={open} />
                  <br />
                  <a href={villageHref(v.id, "place:units")} onClick={open(v.id, "place:units")}>» Troops</a>
                </td>
              )}
              <td className="nowrap">{what}</td>
              <UnitCells units={units(v)} />
            </tr>
          )),
        )}
      </tbody>
    </table>
  );
}

const COMMAND = {
  ATTACKING: ["attack", "Attack on"],
  SUPPORTING: ["support", "Support for"],
  RETURNING: ["return", "Return from"],
  INCOMING_ATTACK: ["attack", "Attack from"],
  SUPPORT_IN: ["support", "Support from"],
};

function CommandCell({ m }) {
  const [icon, text] = COMMAND[m.type] ?? ["other_back", m.type];
  return (
    <span className="nowrap">
      <img src={`/graphic/command/${icon}.png`} alt="" /> {`${text} ${m.otherVillageName} (${m.otherX}|${m.otherY})`}
    </span>
  );
}

const byArrival = (a, b) => new Date(a.m.arrivesAt) - new Date(b.m.arrivesAt);

export function CommandsView({ rows, open }) {
  const all = rows.flatMap((v) => v.commands.map((m) => ({ v, m }))).sort(byArrival);
  return (
    <table className="vis overview_table" width="100%">
      <tbody>
        <tr>
          <th>Command ({all.length})</th>
          <th>Village</th>
          <th>Arrival</th>
          <th>Arrives in</th>
          <UnitHeads />
        </tr>
        {all.map(({ v, m }, i) => (
          <tr key={`${m.type}-${m.id}`} className={i % 2 ? "row_b" : "row_a"}>
            <td><CommandCell m={m} /></td>
            <td className="nowrap"><VillageLink v={v} open={open} screen="place" /></td>
            <td className="nowrap">{fmtOnTime(m.arrivesAt)}</td>
            <td><Timer target={m.arrivesAt} /></td>
            <UnitCells units={m.units} />
          </tr>
        ))}
        {all.length === 0 && (
          <tr>
            <td colSpan={4 + COMBINED_UNITS.length}>No commands.</td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

export function IncomingView({ rows, open }) {
  const all = rows.flatMap((v) => v.incoming.map((m) => ({ v, m }))).sort(byArrival);
  return (
    <table className="vis overview_table" width="100%">
      <tbody>
        <tr>
          <th>Command ({all.length})</th>
          <th>Destination</th>
          <th>Arrival</th>
          <th>Arrives in</th>
        </tr>
        {all.map(({ v, m }, i) => (
          <tr key={`${m.type}-${m.id}`} className={i % 2 ? "row_b" : "row_a"}>
            <td><CommandCell m={m} /></td>
            <td className="nowrap"><VillageLink v={v} open={open} screen="place" /></td>
            <td className="nowrap">{fmtOnTime(m.arrivesAt)}</td>
            <td><Timer target={m.arrivesAt} /></td>
          </tr>
        ))}
        {all.length === 0 && (
          <tr>
            <td colSpan="4">No incoming troops.</td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

export function BuildingsView({ rows, open }) {
  return (
    <table className="vis overview_table" width="100%">
      <tbody>
        <tr>
          <th>Village ({rows.length})</th>
          <th>Points</th>
          {ORDER.map((id) => (
            <th key={id} style={{ textAlign: "center" }}>
              <img src={`/graphic/buildings/${id}.png`} alt={B[id].name} title={B[id].name} />
            </th>
          ))}
        </tr>
        {rows.map((v, i) => (
          <tr key={v.id} className={i % 2 ? "row_b" : "row_a"}>
            <td className="nowrap"><VillageLink v={v} open={open} screen="building:HEADQUARTERS" /></td>
            <td>{v.points}</td>
            {ORDER.map((id) => {
              const level = v.buildings[B[id].type] ?? 0;
              const queued = v.buildQueue.filter((q) => q.type === B[id].type);
              const target = queued.length ? queued[queued.length - 1].targetLevel : null;
              return (
                <td key={id} style={{ textAlign: "center" }} className={level || target != null ? undefined : "hidden"}>
                  {level}
                  {target != null && <span className="grey" title="queued">{` →${target}`}</span>}
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function ResearchView({ rows, open }) {
  const TECH_UNITS = techUnits();
  return (
    <>
      <table className="vis overview_table" width="100%">
        <tbody>
          <tr>
            <th>Village ({rows.length})</th>
            {TECH_UNITS.map(([id, , name]) => (
              <th key={id} style={{ textAlign: "center" }}>
                <img src={`/graphic/unit/unit_${id}.png`} alt={name} title={name} />
              </th>
            ))}
          </tr>
          {rows.map((v, i) => (
            <tr key={v.id} className={i % 2 ? "row_b" : "row_a"}>
              <td className="nowrap"><VillageLink v={v} open={open} screen="smith" /></td>
              {TECH_UNITS.map(([id, type, name]) => {
                const state = v.researched.includes(type) ? ["green", "researched"] : v.researching.includes(type) ? ["yellow", "being researched"] : ["grey", "not researched"];
                return (
                  <td key={id} style={{ textAlign: "center" }}>
                    <img src={`/graphic/dots/${state[0]}.png`} alt={state[1]} title={`${name}: ${state[1]}`} />
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <img src="/graphic/dots/green.png" alt="" /> researched{" "}
        <img src="/graphic/dots/yellow.png" alt="" /> being researched{" "}
        <img src="/graphic/dots/grey.png" alt="" /> not researched
      </p>
    </>
  );
}

const VIEWS = { prod: ProductionView, units: TroopsView, commands: CommandsView, incomings: IncomingView, buildings: BuildingsView, tech: ResearchView };

export function OverviewModeView({ mode, village, go, onSelectVillage, inGroup }) {
  const { rows, error } = useVillageOverview();
  const open = useOpen(village, go, onSelectVillage);
  const View = VIEWS[mode];
  if (error) return <div className="error_box">{error}</div>;
  if (!rows) return <p>Loading…</p>;
  return <View rows={inGroup ? inGroup(rows) : rows} open={open} />;
}
