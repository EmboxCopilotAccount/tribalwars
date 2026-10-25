/* Hand-over to the rally point simulator ("» Insert troops into the simulator" in a report): the form values are put
   into sessionStorage, the simulator fills its form with them once when it opens. Shape: the SimMode form state
   ({att_spear: "12", def_wall: "5", moral: "100", luck: "-3", night: true, ...}). */
const KEY = "twlan.sim.preset";

export function setSimPreset(form) {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(form));
  } catch {
    /* storage unavailable: the simulator just opens empty */
  }
}

// read in the form's state initializer (which React may call twice), cleared once the form is shown
export function peekSimPreset() {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function clearSimPreset() {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    /* nothing to clear */
  }
}

/** {unitId: n} of both sides + the battle's circumstances -> simulator form state */
export function simForm({ att = {}, def = {}, wall, morale, luck, night }) {
  const form = { moral: String(morale ?? 100), luck: String(luck ?? 0), night: !!night };
  for (const [id, n] of Object.entries(att)) if (n > 0) form["att_" + id] = String(n);
  for (const [id, n] of Object.entries(def)) if (n > 0) form["def_" + id] = String(n);
  if (wall != null) form.def_wall = String(wall);
  return form;
}
