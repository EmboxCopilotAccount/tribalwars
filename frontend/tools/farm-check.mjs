#!/usr/bin/env node
// Logic checks for src/lib/farm.js (the farm assistant's target list, templates and report history).
//   node tools/farm-check.mjs
import assert from 'node:assert/strict'
import { createServer } from 'vite'

const server = await createServer({ server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'silent' })
const { contextButtons } = await server.ssrLoadModule('/src/lib/map.js')
const { normalizeConfig, DEFAULT_SETTINGS, TEMPLATE_KEYS, visibleTemplateKeys } = await server.ssrLoadModule('/src/lib/farm.js')
const { farmTargets, templateProblem, lastReports, cleanUnits, toBackendUnits, ago, reportDot, scoutsOnly, reportLoot, plunderable } = await server.ssrLoadModule('/src/lib/farm.js')
const me = { id: 1, x: 500, y: 500, myVillages: [{ id: 1 }, { id: 2 }] }
const vs = [
  { id: 1, x: 500, y: 500, ownerType: 'PLAYER', points: 100 },
  { id: 2, x: 501, y: 500, ownerType: 'PLAYER', points: 100 },          // own second village: never a target
  { id: 3, x: 503, y: 504, ownerType: 'BARBARIAN', points: 50 },         // distance 5
  { id: 4, x: 510, y: 500, ownerType: 'BARBARIAN', points: 300 },        // distance 10, too many points below
  { id: 5, x: 500, y: 530, ownerType: 'BARBARIAN', points: 50 },         // distance 30 (over the limit)
  { id: 6, x: 502, y: 500, ownerType: 'PLAYER', ownerNpc: true, points: 80 },
  { id: 7, x: 498, y: 500, ownerType: 'PLAYER', ownerNpc: false, points: 80 }, // a real player: never
]
const base = { maxDistance: 25, minPoints: 0, maxPoints: 0, npc: false }
assert.deepEqual(farmTargets(me, vs, base, []).map(v => v.id), [3, 4])
assert.deepEqual(farmTargets(me, vs, { ...base, npc: true }, []).map(v => v.id), [6, 3, 4])
assert.deepEqual(farmTargets(me, vs, { ...base, maxPoints: 100 }, []).map(v => v.id), [3])
assert.deepEqual(farmTargets(me, vs, { ...base, maxDistance: 0 }, [3]).map(v => v.id), [4, 5])   // 0 = unlimited, 3 hidden
assert.equal(templateProblem({}, { spear: 5 }), 'This template is empty')
assert.equal(templateProblem({ spear: 6 }, { spear: 5 }), 'Not enough units at home')
assert.equal(templateProblem({ spear: 5 }, { spear: 5 }), null)
assert.deepEqual(cleanUnits({ spear: '7', sword: 0, bogus: 3, axe: -2, light: 2.9 }), { spear: 7, light: 2 })
assert.deepEqual(toBackendUnits({ spear: 3, spy: 1, knight: 1 }), { SPEAR: 3, SCOUT: 1, PALADIN: 1 })
const rep = (o) => ({ defenderView: false, outcome: 'ATTACKER_WIN', attackerLosses: {}, lootWood: 100, lootClay: 100, lootIron: 100, lootCapacity: 300, ...o })
const m = lastReports([
  rep({ id: 1, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z', wallBefore: 4, wallAfter: 3, lootCapacity: 900 }),
  rep({ id: 2, defenderVillageId: 9, occurredAt: '2026-09-21T11:00:00Z', wallBefore: 3, wallAfter: 2 }),
  rep({ id: 3, defenderVillageId: null, occurredAt: '2026-09-21T12:00:00Z' }),          // old report without ids: skipped
  rep({ id: 4, defenderVillageId: 9, defenderView: true, occurredAt: '2026-09-21T13:00:00Z' }), // somebody else's attack on us
])
assert.equal(m.size, 1)
assert.equal(m.get(9).battle.report.id, 2)     // the newest of the player's own battles
assert.equal(m.get(9).battle.full, true)       // 300 loot of 300 capacity
assert.equal(m.get(9).wall, 2)                 // the rams' damage

// a raid with scouts along is still the last battle; the scouts' wall is from before the rams hit
const spy = (level, buildings = {}) => ({ level, wood: 0, clay: 0, iron: 0, buildings, unitsHome: {}, unitsAway: {} })
const mixed = lastReports([
  rep({ id: 10, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z', attackerUnits: { LIGHT: 10 } }),
  rep({ id: 11, defenderVillageId: 9, occurredAt: '2026-09-21T11:00:00Z', attackerUnits: { LIGHT: 10, SCOUT: 1, RAM: 5 },
    spy: spy(2, { WALL: 3 }), wallBefore: 3, wallAfter: 1 }),
]).get(9)
assert.equal(mixed.battle.report.id, 11)
assert.equal(mixed.spy.id, 11)
assert.equal(mixed.wall, 1)
// a scouting whose scouts all died says nothing: the last raid stays
const failed = lastReports([
  rep({ id: 12, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z', attackerUnits: { LIGHT: 10 } }),
  rep({ id: 13, defenderVillageId: 9, occurredAt: '2026-09-21T11:00:00Z', attackerUnits: { SCOUT: 5 }, outcome: 'DEFENDER_WIN', lootWood: 0, lootClay: 0, lootIron: 0 }),
])
assert.equal(failed.get(9).battle.report.id, 12)
assert.equal(lastReports([failed.get(9).battle.report, rep({ id: 14, defenderVillageId: 8, attackerUnits: { SCOUT: 1 }, outcome: 'DEFENDER_WIN', occurredAt: '2026-09-21T11:00:00Z' })]).has(8), false)
// a lost battle or one without rams does not show the wall; scouts that saw no wall saw level 0
const walls = lastReports([
  rep({ id: 15, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z', attackerUnits: { SCOUT: 2 }, spy: spy(2, { WALL: 4 }) }),
  rep({ id: 16, defenderVillageId: 9, occurredAt: '2026-09-21T11:00:00Z', attackerUnits: { AXE: 5 }, outcome: 'DEFENDER_WIN', wallBefore: 4, wallAfter: 4 }),
  rep({ id: 17, defenderVillageId: 8, occurredAt: '2026-09-21T10:00:00Z', attackerUnits: { AXE: 5 }, wallBefore: 2, wallAfter: 2 }),
  rep({ id: 18, defenderVillageId: 7, occurredAt: '2026-09-21T10:00:00Z', attackerUnits: { SCOUT: 2 }, spy: spy(1) }),
  rep({ id: 19, defenderVillageId: 7, occurredAt: '2026-09-21T11:00:00Z', attackerUnits: { SCOUT: 2 }, spy: spy(2, { MAIN: 3 }) }),
])
assert.equal(walls.get(9).wall, 4)
assert.equal(walls.get(9).battle.report.id, 16)
assert.equal(walls.get(8).wall, undefined)
assert.equal(walls.get(7).wall, 0)
assert.equal(walls.get(7).battle, undefined)
// reports of the same moment: the later one (higher id) wins, whatever order they came in
const tie = lastReports([
  rep({ id: 21, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z' }),
  rep({ id: 20, defenderVillageId: 9, occurredAt: '2026-09-21T10:00:00Z' }),
])
assert.equal(tie.get(9).battle.report.id, 21)
// the haul is counted like the report shows it, and a full haul is full despite the fractions
assert.deepEqual(reportLoot(rep({ lootWood: 99.6, lootClay: 100.3, lootIron: 100.1 })), { wood: 99, clay: 100, iron: 100, total: 299, full: true })
assert.equal(reportLoot(rep({ lootWood: 50, lootClay: 50, lootIron: 50 })).full, false)
assert.equal(reportLoot(rep({ lootCapacity: 0, lootWood: 0, lootClay: 0, lootIron: 0 })).full, false)
assert.equal(scoutsOnly({ attackerUnits: { SCOUT: 3 } }), true)
assert.equal(scoutsOnly({ attackerUnits: { SCOUT: 3, LIGHT: 1 }, spy: spy(1) }), false)
assert.equal(scoutsOnly({ spy: spy(1) }), true)                 // old report without the attacking units
assert.equal(reportDot(rep({ attackerLosses: { SPEAR: 1 } })), 'yellow')
assert.equal(reportDot(rep({ outcome: 'DEFENDER_WIN' })), 'red')
assert.equal(ago('2026-09-21T10:00:00Z', Date.parse('2026-09-21T10:30:00Z')), '30 min ago')

// the map's click ring: A/B on abandoned villages (in the original's profile/message slots), on computer players'
// villages only when they are farm targets, never on the player's own villages
const ids = (village, farm) => contextButtons(village, 1, new Set(), farm).map(b => b.id)
const at = (village, farm, id) => { const b = contextButtons(village, 1, new Set(), farm).find(x => x.id === id); return b && [b.dx, b.dy] }
const barb = { id: 9, owner: 0, points: 40 }
const npcV = { id: 8, owner: 2, npc: true, points: 90 }
const human = { id: 7, owner: 2, npc: false, points: 90 }
const mine = { id: 5, owner: 1, points: 90 }
const both = { a: true, b: true, npc: false }
assert.deepEqual(ids(barb, both).filter(i => i.startsWith('mp_farm')), ['mp_farm_a', 'mp_farm_b'])
assert.deepEqual(at(barb, both, 'mp_farm_a'), [20, -30])
assert.deepEqual(at(barb, both, 'mp_farm_b'), [20, 6])
assert.deepEqual(ids(barb, { a: false, b: true, npc: false }).filter(i => i.startsWith('mp_farm')), ['mp_farm_b'])
assert.deepEqual(ids(barb, null).filter(i => i.startsWith('mp_farm')), [])       // no template set
assert.deepEqual(ids(npcV, both).filter(i => i.startsWith('mp_farm')), [])       // npc villages are not targets by default
const npcOn = { a: true, b: true, npc: true }
assert.deepEqual(ids(npcV, npcOn).filter(i => i.startsWith('mp_farm')), ['mp_farm_a', 'mp_farm_b'])
assert.deepEqual(at(npcV, npcOn, 'mp_farm_a'), [-12, -49])                       // the free slot of the noble claim
assert.deepEqual(at(npcV, npcOn, 'mp_farm_b'), [20, 6])                          // where "write message" was
assert.equal(ids(npcV, npcOn).includes('mp_msg'), false)
assert.deepEqual(at(npcV, npcOn, 'mp_profile'), [20, -30])                        // no two buttons share a slot
const slots = contextButtons(npcV, 1, new Set(), npcOn).map(b => b.dx + ',' + b.dy)
assert.equal(new Set(slots).size, slots.length)
assert.deepEqual(ids(human, npcOn).filter(i => i.startsWith('mp_farm')), [])      // real players are never farm targets
assert.deepEqual(ids(mine, npcOn).filter(i => i.startsWith('mp_farm')), [])

// what the server (or an older browser copy) hands back is cleaned into a complete setup
const noTemplates = Object.fromEntries(TEMPLATE_KEYS.map((n) => [n, {}]))
assert.deepEqual(TEMPLATE_KEYS.length, 26)
assert.deepEqual(normalizeConfig(null), { templates: noTemplates, settings: DEFAULT_SETTINGS, hidden: [] })
assert.deepEqual(
  normalizeConfig({ templates: { A: { spear: '5', bogus: 3 }, D: { axe: 1 }, AA: { axe: 1 } }, settings: { npc: true }, hidden: [3, 'x', 4.5, 8] }),
  { templates: { ...noTemplates, A: { spear: 5 }, D: { axe: 1 } }, settings: { ...DEFAULT_SETTINGS, npc: true }, hidden: [3, 8] })
// the editor: A to C at first, then one empty row after the last template with troops, never past Z
assert.deepEqual(visibleTemplateKeys({}), ['A', 'B', 'C'])
assert.deepEqual(visibleTemplateKeys({ A: { spear: 1 } }), ['A', 'B', 'C'])
assert.deepEqual(visibleTemplateKeys({ C: { spear: 1 } }), ['A', 'B', 'C', 'D'])
assert.deepEqual(visibleTemplateKeys({ A: { spear: 1 }, E: { axe: 1 }, F: {} }), ['A', 'B', 'C', 'D', 'E', 'F'])
assert.deepEqual(visibleTemplateKeys({ Z: { spear: 1 } }), TEMPLATE_KEYS)
// resources to plunder: the latest scouting minus the hiding place (known from level 2), minus a raid the scouts came with
{
  const t = (h) => new Date(Date.UTC(2026, 9, 24, h)).toISOString()
  const scout = (id, h, level, res, buildings = {}) => ({ id, occurredAt: t(h), defenderVillageId: 9, outcome: 'ATTACKER_WIN',
    attackerUnits: { SCOUT: 5 }, attackerLosses: {}, spy: { level, ...res, buildings } })
  const raid = (id, h, extra = {}) => ({ id, occurredAt: t(h), defenderVillageId: 9, outcome: 'ATTACKER_WIN',
    attackerUnits: { LIGHT: 10 }, attackerLosses: {}, lootWood: 0, lootClay: 0, lootIron: 0, lootCapacity: 800, ...extra })
  const res = { wood: 1000, clay: 50, iron: 400 }
  assert.equal(plunderable(lastReports([raid(1, 1)]).get(9)), null)                       // never scouted
  let p = plunderable(lastReports([scout(1, 1, 1, res)]).get(9))                          // level 1: hiding place unknown
  assert.deepEqual([p.wood, p.clay, p.iron, p.total, p.hideKnown, p.stale], [1000, 50, 400, 1450, false, false])
  p = plunderable(lastReports([scout(1, 1, 2, res, { HIDING_PLACE: 1 })]).get(9))        // level 1 hides 150 of each
  assert.deepEqual([p.wood, p.clay, p.iron, p.hideKnown], [850, 0, 250, true])
  p = plunderable(lastReports([scout(1, 1, 2, res, {}), raid(2, 2)]).get(9))              // attacked after the scouting
  assert.deepEqual([p.total, p.stale], [1450, true])
  p = plunderable(lastReports([raid(1, 1), scout(2, 2, 2, res, {})]).get(9))              // scouted after the attack
  assert.equal(p.stale, false)
  const mixed = { ...raid(1, 1, { lootWood: 600, lootClay: 50, lootIron: 150 }), attackerUnits: { LIGHT: 10, SCOUT: 2 },
    spy: { level: 2, ...res, buildings: {} } }                                           // scouts rode along: the haul comes off
  p = plunderable(lastReports([mixed]).get(9))
  assert.deepEqual([p.wood, p.clay, p.iron, p.stale], [400, 0, 250, false])
}

console.log('farm.js checks passed')
await server.close()
