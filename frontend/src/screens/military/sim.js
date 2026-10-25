// Client-side battle simulator for the rally point "Simulator" tab (classic Tribal Wars maths,
// using the unit numbers the original ships). Returns {attackingArmy:{before,after}, defendingArmy:{before,after}, wall?}.
import { UNIT_LIST } from "../../lib/military";

const num = (v) => {
  const n = parseInt(v, 10);
  return isNaN(n) || n < 0 ? 0 : n;
};

const CATEGORY = { infantry: "infantry", cavalry: "cavalry", archer: "archer", other: "infantry" };
const kindOf = (u) => (u.id === "spy" ? "infantry" : CATEGORY[u.type] ?? "infantry");

// BattleCalculator.wallInBattle: half the rams knock levels off the wall during the battle (1.2^level hits each, at least 2)
function wallInBattle(rams, wall) {
  let left = Math.floor(rams / 2);
  let level = wall;
  while (level > 0 && left >= Math.max(2, Math.ceil(Math.pow(1.2, level)))) {
    left -= Math.max(2, Math.ceil(Math.pow(1.2, level)));
    level--;
  }
  return level;
}

// The world settings the backend fights with (VillageStateDto): basic defence, night factor and whether rams lower
// the wall during the battle. Defaults = the defaults of WorldSettings.
export function simulate({ att, def, wall = 0, moral = 100, luck = 0, night = false, beliefAtt = false, beliefDef = false,
  basicDefense = 20, nightBonus = 2, destroyBuildings = true }) {
  const attBefore = {};
  const defBefore = {};
  for (const u of UNIT_LIST) {
    attBefore[u.id] = num(att[u.id]);
    defBefore[u.id] = num(def[u.id]);
  }
  // an attack of scouts only is spying: no battle, just the scout duel (BattleCalculator.scoutDuel)
  const attacking = UNIT_LIST.filter((u) => attBefore[u.id] > 0);
  if (attacking.length > 0 && attacking.every((u) => u.id === "spy")) return scoutDuel(attBefore, defBefore);
  const morale = Math.min(100, Math.max(0, Number(moral) || 100)) / 100;
  const luckF = 1 + (Math.min(25, Math.max(-25, Number(luck) || 0))) / 100;
  // the world has no church (world.church = false), so belief never weakens either side; the two checkboxes of the
  // original's form are kept but have no effect. Everything else is the maths of the backend's BattleCalculator.
  void beliefAtt;
  void beliefDef;
  const faithAtt = 1;
  const faithDef = 1;

  // attack power split by category
  const off = { infantry: 0, cavalry: 0, archer: 0 };
  let offTotal = 0;
  for (const u of UNIT_LIST) {
    const p = attBefore[u.id] * u.attack;
    off[kindOf(u)] += p;
    offTotal += p;
  }
  offTotal *= morale * luckF * faithAtt;

  // defence: weighted by what is attacking
  const wallLevel = destroyBuildings ? wallInBattle(attBefore.ram ?? 0, num(wall)) : num(wall);
  const wallBonus = Math.pow(1.037, wallLevel) * (night ? Math.max(1, Number(nightBonus) || 1) : 1);
  let defTotal = 0;
  const shares =
    offTotal > 0
      ? { infantry: off.infantry / (off.infantry + off.cavalry + off.archer), cavalry: off.cavalry / (off.infantry + off.cavalry + off.archer), archer: off.archer / (off.infantry + off.cavalry + off.archer) }
      : { infantry: 1, cavalry: 0, archer: 0 };
  for (const u of UNIT_LIST) {
    const per = u.defense * shares.infantry + u.defense_cavalry * shares.cavalry + u.defense_archer * shares.archer;
    defTotal += defBefore[u.id] * per;
  }
  defTotal = (defTotal * wallBonus + Math.max(0, Number(basicDefense) || 0)) * faithDef;

  // BattleCalculator.lose: the losses are rounded (not the survivors), so both sides agree to the unit
  const survive = (before, fraction) => before - Math.min(before, Math.round(before * Math.min(1, fraction)));
  const attAfter = { ...attBefore };
  const defAfter = { ...defBefore };
  if (offTotal > defTotal) {
    const lossRatio = offTotal === 0 ? 0 : Math.pow(defTotal / offTotal, 1.5);
    for (const u of UNIT_LIST) {
      attAfter[u.id] = survive(attBefore[u.id], lossRatio);
      defAfter[u.id] = 0;
    }
  } else {
    const lossRatio = defTotal === 0 ? 0 : Math.pow(offTotal / defTotal, 1.5);
    for (const u of UNIT_LIST) {
      defAfter[u.id] = survive(defBefore[u.id], lossRatio);
      attAfter[u.id] = 0;
    }
  }
  return {
    attackingArmy: { before: attBefore, after: attAfter },
    defendingArmy: { before: defBefore, after: defAfter },
  };
}

function scoutDuel(attBefore, defBefore) {
  const a = attBefore.spy;
  const d = defBefore.spy;
  const attAfter = { ...attBefore };
  const defAfter = { ...defBefore };
  if (a > d) {
    attAfter.spy = a - Math.round(a * (d === 0 ? 0 : Math.pow(d / a, 1.5)));
    defAfter.spy = 0;
  } else {
    attAfter.spy = 0;
    defAfter.spy = d - Math.round(d * Math.min(1, Math.pow(a / Math.max(1, d), 1.5)));
  }
  return { attackingArmy: { before: attBefore, after: attAfter }, defendingArmy: { before: defBefore, after: defAfter }, scouts: true };
}
