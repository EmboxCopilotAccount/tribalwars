/* Farm assistant helpers: premade armies ("templates" A, B, C, ... up to Z), the target filters and which villages are worth a raid.
   The original TWLan has no such page. The setup is one object per world, {templates, settings, hidden}: it is read from
   the browser's localStorage (so the map's click menu can use it synchronously) and mirrored to the server
   (/api/farm), which is how it follows you to another browser. Sending itself goes through the normal attack command. */
import { api } from "../api";
import { ID_TO_TYPE, UNIT_LIST } from "./military";
import { hideCapacity } from "./buildings";

export const TEMPLATE_KEYS = Array.from({ length: 26 }, (_, i) => String.fromCharCode(65 + i)); // A to Z
const SHOWN_TEMPLATES = 3; // the editor starts with A, B and C

/** The templates the editor shows: at least A to C, and one empty row after the last one with troops (up to Z). */
export function visibleTemplateKeys(templates) {
  const last = TEMPLATE_KEYS.findLastIndex((n) => Object.keys(templates?.[n] ?? {}).length > 0);
  return TEMPLATE_KEYS.slice(0, Math.max(SHOWN_TEMPLATES, last + 2));
}
export const DEFAULT_SETTINGS = { maxDistance: 25, minPoints: 0, maxPoints: 0, npc: false }; // 0 = no limit

const configKey = (worldId) => `twlan.farm.config.${worldId ?? "x"}`;
const legacyKey = (name, worldId) => `twlan.farm.${name}.${worldId ?? "x"}`; // the first version kept three separate keys
function read(k, fallback) {
  try {
    return JSON.parse(window.localStorage.getItem(k)) ?? fallback;
  } catch {
    return fallback;
  }
}
function write(k, value) {
  try {
    window.localStorage.setItem(k, JSON.stringify(value));
  } catch {
    /* storage unavailable: the choice just isn't remembered here (the server copy still is) */
  }
}

/** { spear: 5, ... }: only known units with a whole number above zero */
export function cleanUnits(units) {
  const out = {};
  for (const u of UNIT_LIST) {
    const n = Math.floor(Number(units?.[u.id]));
    if (n > 0) out[u.id] = n;
  }
  return out;
}

/** Whatever was stored (or came from the server) -> a complete, clean setup. */
export function normalizeConfig(raw) {
  const hidden = Array.isArray(raw?.hidden) ? raw.hidden.filter((id) => Number.isInteger(id)) : [];
  return {
    templates: Object.fromEntries(TEMPLATE_KEYS.map((n) => [n, cleanUnits(raw?.templates?.[n])])),
    settings: { ...DEFAULT_SETTINGS, ...(raw?.settings ?? {}) },
    hidden,
  };
}

function readConfig(worldId) {
  const stored = read(configKey(worldId), null);
  if (stored) return normalizeConfig(stored);
  return normalizeConfig({
    templates: read(legacyKey("templates", worldId), {}),
    settings: read(legacyKey("settings", worldId), {}),
    hidden: read(legacyKey("hidden", worldId), []),
  });
}

const hasData = (c) =>
  TEMPLATE_KEYS.some((n) => Object.keys(c.templates[n]).length > 0) || c.hidden.length > 0
  || Object.keys(DEFAULT_SETTINGS).some((k) => c.settings[k] !== DEFAULT_SETTINGS[k]);

// ---- mirroring to the server ----
let pushTimer = null;
let changedAt = 0; // bumped by every local edit, so a slow server answer never overwrites a newer edit
const listeners = new Set();

function push(worldId) {
  clearTimeout(pushTimer);
  pushTimer = setTimeout(() => {
    api.saveFarm(JSON.stringify(readConfig(worldId))).catch(() => {
      /* offline or the backend is older: the setup stays in this browser */
    });
  }, 500);
}

function update(worldId, patch) {
  write(configKey(worldId), { ...readConfig(worldId), ...patch });
  changedAt++;
  push(worldId);
}

/** Called when the server's copy replaced the local one (the Farm Assistant page reloads its state). */
export function onFarmSynced(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * Pulls the setup of `worldId` from the server into the browser; when the server has none yet, this browser's setup
 * (if any) is uploaded, so the first device seeds it.
 */
export async function syncFarmConfig(worldId) {
  const before = changedAt;
  let server;
  try {
    server = await api.farm();
  } catch {
    return;
  }
  if (changedAt !== before) return; // edited while waiting: that edit is already on its way to the server
  if (server?.config) {
    try {
      write(configKey(worldId), normalizeConfig(JSON.parse(server.config)));
      listeners.forEach((fn) => fn());
    } catch {
      /* a copy this version cannot read: keep the local one */
    }
  } else if (hasData(readConfig(worldId))) {
    push(worldId);
  }
}

export const loadTemplates = (worldId) => readConfig(worldId).templates;
export const saveTemplates = (worldId, templates) => update(worldId, { templates });
export const loadSettings = (worldId) => readConfig(worldId).settings;
export const saveSettings = (worldId, settings) => update(worldId, { settings });
export const loadHidden = (worldId) => readConfig(worldId).hidden;
export const saveHidden = (worldId, hidden) => update(worldId, { hidden });

/** template ({unitId: n}) -> the backend's {SPEAR: n} */
export function toBackendUnits(template) {
  return Object.fromEntries(Object.entries(template).map(([id, n]) => [ID_TO_TYPE[id], n]));
}

/** null when the template can be sent from `home` ({unitId: n}), else the reason */
export function templateProblem(template, home) {
  const ids = Object.keys(template);
  if (ids.length === 0) return "This template is empty";
  const short = ids.find((id) => (home[id] ?? 0) < template[id]);
  return short ? "Not enough units at home" : null;
}

/**
 * Villages worth a raid from `village`, nearest first: barbarian villages, plus villages of computer players when
 * `npc` is set. Never the player's own, hidden ones or ones outside the distance / points limits.
 */
export function farmTargets(village, villages, settings, hidden) {
  const own = new Set([village.id, ...(village.myVillages ?? []).map((v) => v.id)]);
  const hide = new Set(hidden);
  return villages
    .filter((v) => !own.has(v.id) && !hide.has(v.id) && (v.ownerType === "BARBARIAN" || (settings.npc && v.ownerNpc)))
    .map((v) => ({ ...v, distance: Math.hypot(v.x - village.x, v.y - village.y) }))
    .filter((v) => (settings.maxDistance > 0 ? v.distance <= settings.maxDistance : true)
      && v.points >= (settings.minPoints || 0) && (!settings.maxPoints || v.points <= settings.maxPoints))
    .sort((a, b) => a.distance - b.distance || a.id - b.id);
}

// the colour of a report's dot in the report list (green: won without losses, yellow: won with losses, red: lost)
export function reportDot(r) {
  if (r.outcome !== "ATTACKER_WIN") return "red";
  const lost = Object.values(r.attackerLosses ?? {}).reduce((a, b) => a + b, 0);
  return lost > 0 ? "yellow" : "green";
}

/** An attack of scouts only is a spying mission, not a battle (like CombatService). Old reports without the
 * attacking units: the spy section is the only hint. */
export function scoutsOnly(r) {
  const types = Object.keys(r.attackerUnits ?? {});
  return types.length > 0 ? types.every((t) => t === "SCOUT") : !!r.spy;
}

/** The haul as the report shows it (every resource rounded down); full when the troops carried all they could. */
export function reportLoot(r) {
  const raw = (r.lootWood ?? 0) + (r.lootClay ?? 0) + (r.lootIron ?? 0);
  const wood = Math.floor(r.lootWood ?? 0), clay = Math.floor(r.lootClay ?? 0), iron = Math.floor(r.lootIron ?? 0);
  return { wood, clay, iron, total: wood + clay + iron, full: r.lootCapacity > 0 && raw >= r.lootCapacity - 0.5 };
}

/**
 * What the player's own reports say about each village: the latest battle ({report, loot, full}), the latest
 * scouting and the wall level, but only as far as a report shows it (scouts of level 2+, or the rams' damage).
 * A scouting whose scouts all died says nothing. Reports from before village ids were recorded cannot be matched.
 */
export function lastReports(reports) {
  const out = new Map();
  const order = (a, b) => new Date(a.occurredAt) - new Date(b.occurredAt) || (a.id ?? 0) - (b.id ?? 0);
  for (const r of [...reports].sort(order)) {
    if (r.defenderView || r.defenderVillageId == null) continue;
    const entry = out.get(r.defenderVillageId) ?? {};
    if (r.spy) {
      entry.spy = r;
      if (r.spy.level >= 2) entry.wall = r.spy.buildings?.WALL ?? 0; // no wall is not listed among the buildings
    }
    if (!scoutsOnly(r)) {
      const { total, full } = reportLoot(r);
      entry.battle = { report: r, loot: total, full };
    }
    // the scouts saw the wall before the battle; the rams' damage is what is left after it
    if (r.wallBefore != null && r.wallAfter != null && r.wallAfter < r.wallBefore) entry.wall = r.wallAfter;
    if (entry.spy || entry.battle) out.set(r.defenderVillageId, entry);
  }
  return out;
}

/**
 * What the latest scouting of a village (an entry of lastReports) says is there to plunder: its resources minus what
 * the hiding place keeps out of reach (CombatService), as of that report. The hiding place is only known from scouts of
 * level 2+ (hideKnown false: nothing subtracted). Scouts that came along with a raid saw the village before it was
 * looted, so that raid's haul comes off. stale: a later attack has taken resources since. null without a scouting.
 */
export function plunderable(entry) {
  const r = entry?.spy;
  if (!r) return null;
  const hideKnown = r.spy.level >= 2;
  const level = r.spy.buildings?.HIDING_PLACE ?? 0;
  const hidden = hideKnown && level > 0 ? hideCapacity(level) : 0;
  const battle = entry.battle?.report;
  const looted = battle === r ? reportLoot(r) : { wood: 0, clay: 0, iron: 0 };
  const left = (seen, gone) => Math.max(0, seen - gone - hidden);
  const out = { wood: left(r.spy.wood, looted.wood), clay: left(r.spy.clay, looted.clay), iron: left(r.spy.iron, looted.iron) };
  const stale = !!battle && battle !== r
    && (new Date(battle.occurredAt) - new Date(r.occurredAt) || (battle.id ?? 0) - (r.id ?? 0)) > 0;
  return { ...out, total: out.wood + out.clay + out.iron, at: r.occurredAt, hideKnown, stale };
}

/** "5 min ago", "3 h ago", "2 d ago" */
export function ago(time, now = Date.now()) {
  const s = Math.max(0, Math.floor((now - new Date(time).getTime()) / 1000));
  if (s < 90) return "just now";
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  if (s < 129600) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}
