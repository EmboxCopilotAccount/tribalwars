/* The rally point's named troop templates (screens/military/TroopTemplates.jsx), stored with the farm setup (lib/farm.js). */
import { useEffect, useState } from "react";
import { UNIT_LIST } from "./military";
import { loadTroopTemplates, onFarmSynced, saveTroopTemplates } from "./farm";

/** The world's troop templates, kept in step with server syncs. */
export function useTroopTemplates(worldId) {
  const [list, setList] = useState(() => loadTroopTemplates(worldId));
  useEffect(() => onFarmSynced(() => setList(loadTroopTemplates(worldId))), [worldId]);
  const save = (next) => {
    saveTroopTemplates(worldId, next);
    setList(loadTroopTemplates(worldId));
  };
  return [list, save];
}

/** template -> command form counts, at most what is at home */
export function templateCounts(template, home) {
  const c = {};
  for (const u of UNIT_LIST) {
    const n = Math.min(template.units[u.id] ?? 0, home[u.id] ?? 0);
    if (n > 0) c[u.id] = String(n);
  }
  return c;
}
