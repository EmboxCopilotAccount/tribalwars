/* Hand-over to the rally point's command form ("» Attack again with the same troops" in a report): which village
   sends, and the troops ({unitId: n}, or "all" = every unit at home then). The rally point fills its form once that
   village is the current one. Kept in sessionStorage like lib/simPreset.js. */
const KEY = "twlan.place.preset";

export function setCommandPreset(villageId, units) {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify({ villageId, units }));
  } catch {
    /* storage unavailable: the form just opens with the target only */
  }
}

/** The preset for `villageId` (removed once read), else null. A preset for another village is kept. */
export function takeCommandPreset(villageId) {
  try {
    const p = JSON.parse(window.sessionStorage.getItem(KEY) ?? "null");
    if (!p || p.villageId !== villageId) return null;
    window.sessionStorage.removeItem(KEY);
    return p.units;
  } catch {
    return null;
  }
}
