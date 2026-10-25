// Rally point -> Troop templates (place:templates, original: screen=place&mode=templates). Named troop sets that the
// command form fills in with one click ("Troop template" box next to the form). Kept per world with the Farm
// Assistant's setup (lib/farm.js troopTemplates), so they follow you to another browser.
import { useState } from "react";
import { UNIT_LIST } from "../../lib/military";
import { MAX_TROOP_TEMPLATES, TROOP_TEMPLATE_NAME_MAX, cleanUnits } from "../../lib/farm";
import { useTroopTemplates } from "../../lib/troopTemplates";

const asInputs = (units) => Object.fromEntries(Object.entries(units ?? {}).map(([id, n]) => [id, String(n)]));

export function TroopTemplates({ village, home }) {
  const [list, save] = useTroopTemplates(village.worldId);
  const [editing, setEditing] = useState(null); // index being edited, or "new"
  const [name, setName] = useState("");
  const [units, setUnits] = useState({});
  const [error, setError] = useState(null);

  const open = (i) => {
    setEditing(i);
    setError(null);
    setName(i === "new" ? "" : list[i].name);
    setUnits(i === "new" ? {} : asInputs(list[i].units));
  };
  const submit = (e) => {
    e.preventDefault();
    const clean = cleanUnits(units);
    const trimmed = name.trim();
    if (!trimmed) return setError("Enter a name for the template.");
    if (!Object.keys(clean).length) return setError("Enter at least one unit.");
    if (list.some((t, i) => i !== editing && t.name.toLowerCase() === trimmed.toLowerCase()))
      return setError(`There is already a template called "${trimmed}".`);
    const next = [...list];
    if (editing === "new") next.push({ name: trimmed, units: clean });
    else next[editing] = { name: trimmed, units: clean };
    save(next);
    setEditing(null);
  };
  const remove = (e, i) => {
    e.preventDefault();
    if (!window.confirm(`Delete the template "${list[i].name}"?`)) return;
    save(list.filter((_, j) => j !== i));
    if (editing === i) setEditing(null);
  };

  return (
    <>
      <h3>Troop templates</h3>
      <p>Save troop sets you send often. Pick one in the "Troop template" box when giving a command to fill in the troops.</p>
      <table className="vis" id="troop_templates">
        <tbody>
          <tr>
            <th>Name</th>
            {UNIT_LIST.map((u) => (
              <th key={u.id} style={{ textAlign: "center" }}>
                <img src={`/graphic/unit/unit_${u.id}.png`} title={u.name} alt={u.name} />
              </th>
            ))}
            <th />
          </tr>
          {list.length === 0 && (
            <tr>
              <td colSpan={UNIT_LIST.length + 2} className="grey">No templates yet. Create one below.</td>
            </tr>
          )}
          {list.map((t, i) => (
            <tr key={t.name} className={i % 2 ? "row_b" : "row_a"}>
              <td><b>{t.name}</b></td>
              {UNIT_LIST.map((u) => (
                <td key={u.id} style={{ textAlign: "center" }} className={t.units[u.id] ? undefined : "hidden"}>
                  {t.units[u.id] ?? 0}
                </td>
              ))}
              <td className="nowrap">
                <a href="#" className="template_edit" onClick={(e) => { e.preventDefault(); open(i); }}>Edit</a>{" "}
                | <a href="#" className="template_delete" onClick={(e) => remove(e, i)}>Delete</a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing == null ? (
        list.length < MAX_TROOP_TEMPLATES ? (
          <p>
            <a href="#" id="template_new" onClick={(e) => { e.preventDefault(); open("new"); }}>» Create a new template</a>
          </p>
        ) : (
          <p className="grey">You have {MAX_TROOP_TEMPLATES} templates, the most there can be. Delete one to make room.</p>
        )
      ) : (
        <form id="template_form" onSubmit={submit}>
          <h4>{editing === "new" ? "New template" : `Edit "${list[editing].name}"`}</h4>
          {error && <div className="error_box">{error}</div>}
          <table className="vis">
            <tbody>
              <tr>
                <td>Name:</td>
                <td colSpan={UNIT_LIST.length}>
                  <input type="text" id="template_name" maxLength={TROOP_TEMPLATE_NAME_MAX} value={name} onChange={(e) => setName(e.target.value)} />
                </td>
              </tr>
              <tr>
                <td />
                {UNIT_LIST.map((u) => (
                  <th key={u.id} style={{ textAlign: "center" }}>
                    <img src={`/graphic/unit/unit_${u.id}.png`} title={u.name} alt={u.name} />
                  </th>
                ))}
              </tr>
              <tr>
                <td>Troops:</td>
                {UNIT_LIST.map((u) => (
                  <td key={u.id} style={{ textAlign: "center" }}>
                    <input type="text" name={u.id} style={{ width: 38 }} value={units[u.id] ?? ""}
                      onChange={(e) => setUnits((x) => ({ ...x, [u.id]: e.target.value }))} />
                  </td>
                ))}
              </tr>
              <tr>
                <td className="grey">At home</td>
                {UNIT_LIST.map((u) => (
                  <td key={u.id} className="grey" style={{ textAlign: "center" }}>{home[u.id] ?? 0}</td>
                ))}
              </tr>
            </tbody>
          </table>
          <p>
            <input type="submit" className="btn" id="template_save" value={editing === "new" ? "Create template" : "Save template"} />{" "}
            <a href="#" onClick={(e) => { e.preventDefault(); setEditing(null); }}>Cancel</a>
          </p>
        </form>
      )}
    </>
  );
}
