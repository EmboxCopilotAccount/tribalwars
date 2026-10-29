import { useEffect, useRef, useState } from "react";
import { api } from "../../api";

const swatch = (rgb) => ({ width: 15, minWidth: 15, padding: 0, backgroundColor: rgb });

/* Colour legend below the map plus the "Manage groups" box: the player's map groups (#119), i.e. coloured players,
   tribes and villages, stored on the server. */
export function MapLegend({ groups = [], onChange }) {
  const [open, setOpen] = useState(false);
  const cell = (rgb, text) => (
    <>
      <td style={swatch(rgb)}></td>
      <td style={{ whiteSpace: "normal" }} className="small">
        {text}
      </td>
    </>
  );
  return (
    <>
      <div style={{ float: "left", marginBottom: 15 }} className="containerBorder">
        <table
          style={{
            border: "solid 1px #8c5f0d",
            backgroundColor: "#f4e4bc",
            marginLeft: 0,
            borderCollapse: "separate",
            textAlign: "left",
          }}
        >
          <tbody>
            <tr className="nowrap">
              <td valign="top" className="small">
                Standard:
              </td>
              {cell("rgb(255, 255, 255)", "Selected")}
              {cell("rgb(240, 200, 0)", "Your villages")}
              {cell("rgb(0, 0, 244)", "Your tribe")}
              {cell("rgb(150, 150, 150)", "Abandoned villages")}
              {cell("rgb(130, 60, 10)", "Miscellaneous")}
            </tr>
            <tr className="nowrap">
              <td valign="top" className="small">
                Tribe:
              </td>
              {cell("rgb(0, 160, 244)", "Allies")}
              {cell("rgb(128, 0, 128)", "Non-Aggression-Pact (NAP)")}
              {cell("rgb(244, 0, 0)", "Enemies")}
              <td></td>
              <td></td>
            </tr>
          </tbody>
        </table>
      </div>
      <br />
      <div style={{ width: "100%", textAlign: "left", clear: "both" }}>
        <a
          href="#"
          id="map_manage_groups"
          onClick={(e) => {
            e.preventDefault();
            setOpen(!open);
          }}
        >
          » Manage groups
        </a>
      </div>
      <br />
      <div
        style={{ float: "left", clear: "both", display: open ? "block" : "none" }}
        className="containerBorder"
        id="village_colors"
      >
        <table style={{ backgroundColor: "#f4e4bc", border: "solid 1px #8c5f0d" }}>
          <tbody>
            <tr>
              <td valign="top">
                <h5>Other villages</h5>
                <MapGroups groups={groups} onChange={onChange} />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </>
  );
}

const KINDS = [
  ["PLAYER", "Player", "Player name"],
  ["TRIBE", "Tribe", "Tribe tag or name"],
  ["VILLAGE", "Village", "Coordinates, e.g. 500|500"],
];
const randomColor = () => "#" + Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, "0");

/* The group list: colour, name, shown on the map, its players / tribes / villages, and "» Create new group". */
function MapGroups({ groups, onChange }) {
  const [error, setError] = useState(null);
  const [newGroup, setNewGroup] = useState(null); // { name, color } while the form is open
  const [editing, setEditing] = useState(null); // id of the group whose entries are open
  const [entry, setEntry] = useState({ kind: "PLAYER", query: "" });
  const run = async (call) => {
    try {
      const list = await call();
      setError(null);
      onChange?.(list);
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  };
  const create = async (e) => {
    e.preventDefault();
    if (await run(() => api.createMapGroup(newGroup.name, newGroup.color))) setNewGroup(null);
  };
  const addEntry = async (e, id) => {
    e.preventDefault();
    if (await run(() => api.addMapGroupEntry(id, entry.kind, entry.query))) setEntry({ ...entry, query: "" });
  };
  return (
    <>
      {error && (
        <div className="error" id="map_group_error">
          {error}
        </div>
      )}
      <table id="for_groups" className="vis">
        <tbody>
          {groups.length > 0 && (
            <tr>
              <th>Colour</th>
              <th>Group</th>
              <th>Shown</th>
              <th>Entries</th>
              <th></th>
            </tr>
          )}
          {groups.map((g) => (
            <MapGroupRow
              key={g.id}
              g={g}
              open={editing === g.id}
              onToggle={() => setEditing(editing === g.id ? null : g.id)}
              run={run}
              entry={entry}
              setEntry={setEntry}
              onAddEntry={(e) => addEntry(e, g.id)}
            />
          ))}
          {newGroup && (
            <tr id="new_group">
              <td colSpan={5}>
                <form onSubmit={create}>
                  <input type="color" value={newGroup.color} onChange={(e) => setNewGroup({ ...newGroup, color: e.target.value })} />{" "}
                  <input
                    type="text"
                    name="new_group_name"
                    placeholder="Group name"
                    maxLength={30}
                    autoFocus
                    value={newGroup.name}
                    onChange={(e) => setNewGroup({ ...newGroup, name: e.target.value })}
                  />{" "}
                  <input type="submit" className="btn" value="OK" id="for_new_group" />{" "}
                  <a
                    href="#"
                    onClick={(e) => {
                      e.preventDefault();
                      setNewGroup(null);
                      setError(null);
                    }}
                  >
                    Cancel
                  </a>
                </form>
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <br />
      {!newGroup && (
        <a
          href="#"
          id="map_group_create"
          onClick={(e) => {
            e.preventDefault();
            setNewGroup({ name: "", color: randomColor() });
          }}
        >
          » Create new group
        </a>
      )}
    </>
  );
}

function MapGroupRow({ g, open, onToggle, run, entry, setEntry, onAddEntry }) {
  const hint = KINDS.find((k) => k[0] === entry.kind)[2];
  return (
    <>
      <tr className="map_group">
        <td>
          <ColorInput value={g.color} onCommit={(color) => run(() => api.updateMapGroup(g.id, { color }))} />
        </td>
        <td>{g.name}</td>
        <td align="center">
          <input
            type="checkbox"
            checked={g.shown}
            title="Show on the map"
            onChange={(e) => run(() => api.updateMapGroup(g.id, { shown: e.target.checked }))}
          />
        </td>
        <td>
          <a
            href="#"
            className="map_group_edit"
            onClick={(e) => {
              e.preventDefault();
              onToggle();
            }}
          >
            {g.entries.length} » edit
          </a>
        </td>
        <td>
          <a
            href="#"
            title="Delete group"
            onClick={(e) => {
              e.preventDefault();
              if (window.confirm(`Delete the group "${g.name}"?`)) run(() => api.deleteMapGroup(g.id));
            }}
          >
            <img src="/graphic/delete_small.png" alt="Delete" />
          </a>
        </td>
      </tr>
      {open && (
        <tr className="map_group_entries">
          <td></td>
          <td colSpan={4}>
            {g.entries.length === 0 && <p className="small">No players, tribes or villages yet.</p>}
            {g.entries.map((e) => (
              <div key={e.id} className="small">
                {KINDS.find((k) => k[0] === e.kind)[1]}: {e.label}{" "}
                <a
                  href="#"
                  title="Remove"
                  onClick={(ev) => {
                    ev.preventDefault();
                    run(() => api.removeMapGroupEntry(g.id, e.id));
                  }}
                >
                  [x]
                </a>
              </div>
            ))}
            <form onSubmit={onAddEntry} style={{ marginTop: 4 }}>
              <select value={entry.kind} onChange={(e) => setEntry({ ...entry, kind: e.target.value })}>
                {KINDS.map(([k, label]) => (
                  <option key={k} value={k}>
                    {label}
                  </option>
                ))}
              </select>{" "}
              <input type="text" placeholder={hint} value={entry.query} onChange={(e) => setEntry({ ...entry, query: e.target.value })} />{" "}
              <input type="submit" className="btn" value="Add" />
            </form>
          </td>
        </tr>
      )}
    </>
  );
}

/* A colour picker that saves once the player stops dragging (the input fires on every step). */
function ColorInput({ value, onCommit }) {
  const [shown, setShown] = useState(value);
  const [saved, setSaved] = useState(value);
  const timer = useRef(null);
  if (value !== saved) {
    // the server's answer (or another tab) changed the colour
    setSaved(value);
    setShown(value);
  }
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <input
      type="color"
      title="Colour"
      value={shown}
      onChange={(e) => {
        const c = e.target.value;
        setShown(c);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => onCommit(c), 400);
      }}
    />
  );
}
