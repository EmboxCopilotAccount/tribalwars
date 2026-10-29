// Pieces of the rally point (screen=place). Markup mirrors templates/twlan/controllers/game/place/*.php
// and overview/{outgoing,incoming}Units.php; behaviour mirrors js/VillageTarget.js + game.js helpers.
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { api } from "../../api";
import { PLAYER_NAME, villagePoints } from "../../lib/data";
import {
  PLACE_COLUMNS,
  rawStyle,
  UNIT_BY_ID,
  UNIT_LIST,
  commandInfo,
  continentOf,
  fmtBuildTime,
  fmtOnTime,
  navigateTo,
  cancellableUntil,
  useNowTick,
  villageDisplayName,
  TYPE_TO_ID,
  ID_TO_TYPE,
  unitsAtHome,
  morale,
  isOutgoingCommand,
  isOwnCommand,
} from "../../lib/military";
import { B, confirmBox } from "../../lib/buildings";
import { Timer } from "../../lib/ui";
import { favoriteIds, lastAttacked, recentTargets } from "../../lib/targets";
import { simulate } from "./sim";
import { clearSimPreset, peekSimPreset } from "../../lib/simPreset";
import { templateCounts, useTroopTemplates } from "../../lib/troopTemplates";
import { VillageContext } from "../VillageContext";
import { hashOf } from "../../lib/nav";

export const PLACE_MODES = [
  ["command", "Commands"],
  ["secrets", "Secret"],
  ["units", "Troops"],
  ["sim", "Simulator"],
  ["templates", "Troop-Templates"],
  ["farm", "Farm Assistant"], // not in the original: premade armies, see FarmAssistant.jsx
];

/* ---------------- header + navi ---------------- */
export function PlaceHeader({ mode, navigate }) {
  return (
    <>
      <table width="100%">
        <tbody>
          <tr>
            <td>
              <img src="/graphic/big_buildings/place1.png" alt="Rally point" />
            </td>
            <td width="100%">
              <h2>{"Rally point (Level 1)"}</h2>
              On the rally point your fighters meet. Here you can command your armies.
            </td>
          </tr>
        </tbody>
      </table>
      <br />
      <table cellPadding="0" cellSpacing="0" width="100%" />
      <table className="vis modemenu" width="100%">
        <tbody>
          <tr>
            {PLACE_MODES.map(([m, label]) => (
              <td key={m} className={mode === m ? "selected" : undefined} style={{ minWidth: 80 }}>
                <a href={hashOf("place:" + m)} onClick={(e) => navigate(e, "place:" + m)}>
                  {label}
                </a>
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </>
  );
}

/* ---------------- troop movements (own / foreign commands) ---------------- */
export function CommandTables({ village, go, onCancel, onRename }) {
  const now = useNowTick();
  const all = [...(village.outgoingMovements ?? []), ...(village.incomingMovements ?? [])].map((m) => ({
    ...m,
    ...commandInfo(m),
  })).sort((a, b) => new Date(a.arrivesAt) - new Date(b.arrivesAt));
  // own troops on the road and troops coming in from others are two tables (#84)
  const own = all.filter(isOwnCommand);
  const incoming = all.filter((m) => !isOwnCommand(m));
  const cancellable = (m) => onCancel && cancellableUntil(m, now);
  if (all.length === 0) return null;
  const table = (title, rows, id) => (
    <table className="vis" style={{ width: "100%" }} id={id}>
      <tbody>
        <tr>
          <th width="52%">
            {title} (<span className="command-list-count">{rows.length}</span>)
          </th>
          <th width="33%">Arrival</th>
          <th width="15%">Arrival in</th>
        </tr>
        {rows.map((m) => (
          <tr key={m.icon + m.id}>
            <td>
              <img src={`/graphic/command/${m.icon}.png`} alt="" />{" "}
              <CommandName m={m} go={go} onRename={onRename} />
              {cancellable(m) && (
                <a
                  className="float_right"
                  href="#"
                  title="The troops turn round and walk home as long as they have walked so far"
                  onClick={(e) => {
                    e.preventDefault();
                    confirmBox("Cancel this command? The troops turn round and come home.", () => onCancel(m.id));
                  }}
                >
                  cancel
                </a>
              )}
            </td>
            <td>{fmtOnTime(m.arrivesAt)}</td>
            <td>
              <Timer target={m.arrivesAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
  return (
    <>
      {own.length > 0 && table("Own commands", own, "commands_outgoings")}
      {own.length > 0 && incoming.length > 0 && <br />}
      {incoming.length > 0 && table("Incoming troops", incoming, "commands_incomings")}
    </>
  );
}

/* the command's name with the original's quickedit pencil: click it, type a name, Enter saves, Escape or an empty name
   goes back (empty = the default name). Shared by the rally point and the village overview. */
export function CommandName({ m, go, onRename }) {
  const [draft, setDraft] = useState(null); // null = not editing
  const save = async () => {
    const label = draft.trim();
    setDraft(null);
    if (label !== (m.label ?? "")) await onRename(m.id, !isOutgoingCommand(m), label);
  };
  return (
    <span className="quickedit-out" data-id={m.id} data-ignore-icons="1">
      {draft == null ? (
        <span className="quickedit-content">
          <a href={hashOf("info_command:" + m.id)} onClick={(e) => navigateTo(e, "info_command:" + m.id, go)}>
            <span className="quickedit-label">{m.label}</span>
          </a>{" "}
          {onRename && (
            <a
              className="rename-icon"
              href="#"
              title="Rename"
              onClick={(e) => {
                e.preventDefault();
                setDraft(m.label ?? "");
              }}
            />
          )}
        </span>
      ) : (
        <span className="quickedit-edit">
          <input
            type="text"
            className="command-rename"
            maxLength={COMMAND_LABEL_MAX}
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                save();
              } else if (e.key === "Escape") setDraft(null);
            }}
          />{" "}
          <input type="button" className="btn" value="Rename" onClick={save} />
        </span>
      )}
    </span>
  );
}
const COMMAND_LABEL_MAX = 60; // MovementService.LABEL_MAX

/* ---------------- target field (VillageTarget.js) ---------------- */
const PLACEHOLDER = { coord: "123|456", village_name: "Village name", player_name: "Player name" };

function villageInfo(v, village, from) {
  const isPlayer = v.ownerType === "PLAYER";
  const points = v.id === village.id ? villagePoints(village) : v.points ?? 26;
  const dist = Math.round(Math.sqrt((v.x - from.x) ** 2 + (v.y - from.y) ** 2));
  const img = isPlayer ? "v2_icon.png" : "v1_left_icon.png";
  return { points, dist, img, owner: isPlayer ? v.ownerName ?? PLAYER_NAME : "Barbarian" };
}

function VillageItem({ v, village, from, onClick }) {
  const { points, dist, img, owner } = villageInfo(v, village, from);
  const name = v.name.length > 18 ? v.name.substr(0, 18) + "…" : v.name;
  return (
    <div className="village-item" onClick={onClick}>
      <img className="village-delete" alt="" src="/graphic/delete.png" />
      <img className="village-picture" alt="" src={`/graphic/map/icon/${img}`} />
      <span className="village-name">{`${name} (${v.x}|${v.y})`}</span>
      <span className="village-info">
        <strong>Owner:</strong> {owner} <strong>Points:</strong> {points}
      </span>
      <span className="village-distance">
        <strong>Distance:</strong> {dist === 1 ? `${dist} field` : `${dist} fields`}
      </span>
    </div>
  );
}

/* the "village_targets" popup (UI.AjaxPopup): the map's favorites or the recently used targets */
function TargetsPopup({ kind, history, village, villages, rect, onPick, onClose }) {
  const ids = kind === "favorites" ? favoriteIds() : recentTargets(village.worldId, history).map((e) => e.id);
  const items = ids.map((id) => villages.find((v) => v.id === id)).filter(Boolean);
  return createPortal(
    <div className="popup_helper">
      <div id="popup_box_village_targets" className="popup_style" style={{ display: "block", width: 400, position: "absolute", top: rect.bottom + window.pageYOffset + 2, left: rect.left + window.pageXOffset }} onClick={(e) => e.stopPropagation()}>
        <div className="popup_menu">
          {kind === "favorites" ? "Favorites" : "History"}
          <a href="#" onClick={(e) => { e.preventDefault(); onClose(); }}>X</a>
        </div>
        <div className="popup_content" style={{ maxHeight: 300, overflowY: "auto" }}>
          {items.length === 0 && (
            <p className="grey">{kind === "favorites" ? "No favorites yet. Star villages on the map." : history === "market" ? "You have not sent any resources yet." : "You have not sent any troops yet."}</p>
          )}
          {items.map((v) => (
            <div key={v.id} className="target-select-autocomplete" style={{ position: "static", maxHeight: "none" }}>
              <VillageItem v={v} village={village} from={village} onClick={(e) => { e.stopPropagation(); onPick(v); }} />
            </div>
          ))}
        </div>
      </div>
    </div>,
    document.body
  );
}

/* `history` "market": the History popup lists where the market shipped to, and there is no "last attacked" button */
export function TargetField({ village, villages, type, setType, text, setText, confirmed, setConfirmed, history }) {
  const boxRef = useRef(null);
  const [pos, setPos] = useState(null);
  const [selected, setSelected] = useState(null);
  const [showList, setShowList] = useState(false);
  const [list, setList] = useState(null); // { kind: "favorites" | "recent", rect }: the popup under the links
  const linksRef = useRef(null);

  const needle = text.trim().toLowerCase();
  const matches =
    !needle || confirmed
      ? []
      : villages.filter((v) => {
          if (type === "coord") return `${v.x}|${v.y}`.startsWith(needle) && needle.length >= 3;
          if (type === "village_name") return needle.length >= 2 && v.name.toLowerCase().includes(needle);
          return needle.length >= 2 && v.ownerType === "PLAYER" && (v.ownerName ?? PLAYER_NAME).toLowerCase().includes(needle);
        });

  useEffect(() => {
    const onClick = () => setShowList(false);
    window.addEventListener("click", onClick);
    return () => window.removeEventListener("click", onClick);
  }, []);
  useEffect(() => {
    if (!boxRef.current) return;
    const r = boxRef.current.getBoundingClientRect();
    setPos({ top: r.bottom + window.pageYOffset + 2, left: r.left + window.pageXOffset });
  }, [text, showList, type]);

  // the original re-checks x/y hidden inputs every 100ms; here we resolve coordinates as they are typed
  const confirm = (v) => {
    setConfirmed(v);
    setShowList(false);
    setSelected(null);
  };
  const remove = () => {
    setConfirmed(null);
    setText("");
  };

  const onChange = (e) => {
    let val = e.target.value;
    const deleting = e.nativeEvent?.inputType?.startsWith("delete");
    if (type === "coord") {
      val = val.replace(/[,.]/, "|");
      val = val.replace(/[^0-9|]+/, "");
      if (val.length === 3 && !deleting) val = val + "|";
      if (val.indexOf("||") !== -1) val = val.replace(/(\|{2,})/, "|");
      if (val.length > 7) val = val.substr(0, 7);
    }
    setText(val);
    setShowList(true);
    setSelected(null);
    if (type === "coord") {
      const m = val.match(/^(\d{1,3})\|(\d{1,3})$/);
      const v = m && villages.find((x) => x.x === Number(m[1]) && x.y === Number(m[2]));
      if (v) confirm(v);
    }
  };
  const onKeyDown = (e) => {
    if (e.keyCode === 38) {
      setSelected((s) => (s === null || s <= 0 ? s : s - 1));
      e.preventDefault();
    } else if (e.keyCode === 40) {
      setSelected((s) => (s === null ? 0 : Math.min(s + 1, matches.length - 1)));
      e.preventDefault();
    } else if (e.keyCode === 13) {
      if (selected !== null && matches[selected]) confirm(matches[selected]);
      else if (matches.length === 1) confirm(matches[0]);
      e.preventDefault();
    }
  };

  return (
    <div className="target-select clearfix vis float_left">
      <h4>Target:</h4>

      <table className="vis" style={{ width: "100%" }}>
        <tbody>
          <tr>
            <td>
              <div className="target-types">
                {[
                  ["coord", " Coordinates"],
                  ["village_name", " Village name"],
                  ["player_name", " Player name"],
                ].map(([v, label]) => (
                  <label key={v}>
                    <input
                      type="radio"
                      name="target_type"
                      value={v}
                      checked={type === v}
                      ref={(el) => el && v === "coord" && el.setAttribute("checked", "checked")}
                      onChange={() => {
                        setType(v);
                        remove();
                      }}
                    />
                    {label}
                  </label>
                ))}
              </div>

              <div id="place_target" className="target-input float_left" ref={boxRef}>
                <span role="status" aria-live="polite" className="ui-helper-hidden-accessible" />
                <input
                  type="text"
                  name="input"
                  className="target-input-field target-input-autocomplete ui-autocomplete-input"
                  data-type="player"
                  value={text}
                  autoComplete="off"
                  tabIndex="14"
                  placeholder={PLACEHOLDER[type]}
                  style={confirmed ? { display: "none" } : undefined}
                  onChange={onChange}
                  onKeyDown={onKeyDown}
                  onClick={(e) => e.stopPropagation()}
                />
                {confirmed && <VillageItem v={confirmed} village={village} from={village} onClick={(e) => { e.stopPropagation(); remove(); }} />}
              </div>
              {history !== "market" && <a
                href="#"
                className="target-quickbutton target-last-attacked"
                title="Last attacked"
                onClick={(e) => {
                  e.preventDefault();
                  const id = lastAttacked(village.worldId);
                  const v = id != null && villages.find((x) => x.id === id);
                  if (v) confirm(v);
                }}
              />}
            </td>
          </tr>
          <tr>
            <td>
              <div className="target-select-links" ref={linksRef}>
                <a href="#" onClick={(e) => { e.preventDefault(); e.stopPropagation(); setList(list?.kind === "favorites" ? null : { kind: "favorites", rect: linksRef.current.getBoundingClientRect() }); }}>» Favorites</a>
                <a href="#" onClick={(e) => { e.preventDefault(); e.stopPropagation(); setList(list?.kind === "recent" ? null : { kind: "recent", rect: linksRef.current.getBoundingClientRect() }); }}>» History</a>
                <span />
                <span />
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      {list && (
        <TargetsPopup
          kind={list.kind}
          history={history}
          village={village}
          villages={villages}
          rect={list.rect}
          onPick={(v) => { setList(null); confirm(v); }}
          onClose={() => setList(null)}
        />
      )}
      {showList &&
        matches.length > 0 &&
        pos &&
        createPortal(
          <div className="target-select-autocomplete" style={{ top: pos.top, left: pos.left, maxHeight: 250 }}>
            {matches.map((v, i) => (
              <div key={v.id} className={i === selected ? "village-item village-selected" : "village-item"} onClick={(e) => { e.stopPropagation(); confirm(v); }}>
                <img className="village-delete" alt="" src="/graphic/delete.png" />
                <img className="village-picture" alt="" src={`/graphic/map/icon/${villageInfo(v, village, village).img}`} />
                <span className="village-name">{`${v.name} (${v.x}|${v.y})`}</span>
                <span className="village-info">
                  <strong>Owner:</strong> {villageInfo(v, village, village).owner} <strong>Points:</strong> {villageInfo(v, village, village).points}
                </span>
                <span className="village-distance">
                  <strong>Distance:</strong> {villageInfo(v, village, village).dist} fields
                </span>
              </div>
            ))}
          </div>,
          document.body
        )}
    </div>
  );
}

/* ---------------- command form (command.php) ---------------- */
export function CommandForm({ village, home, counts, setCounts, popup, target, onSubmit, onCancelCommand, onRenameCommand, go }) {
  const [selectAll, setSelectAll] = useState(false);
  const clicked = useRef("attack");
  const [troopTemplates] = useTroopTemplates(village.worldId);

  const setUnit = (id, val) => setCounts({ ...counts, [id]: val });
  const insertUnit = (e, id, max) => {
    e.preventDefault();
    setCounts({ ...counts, [id]: String(max) === String(counts[id] ?? "") ? "" : String(max) });
  };
  const doSelectAll = (e) => {
    e.preventDefault();
    const next = !selectAll;
    setSelectAll(next);
    const c = {};
    for (const u of UNIT_LIST) c[u.id] = next && home[u.id] > 0 ? String(home[u.id]) : "";
    setCounts(c);
  };

  return (
    <>
      <h3>Give commands</h3>
      <link rel="stylesheet" type="text/css" href="/village_target.css" />
      <form
        id="units_form"
        name="units"
        method="post"
        className="float_left"
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit(clicked.current);
        }}
      >
        <input type="hidden" name="1d6571b9ece0685b692178" value="8e73f3161d6571" readOnly />
        <input type="hidden" id="template_id" name="template_id" value="" readOnly />
        <table>
          <tbody>
            <tr style={{ display: "none" }}>
              <td />
            </tr>
            <tr>
              {PLACE_COLUMNS.map((col, i) => (
                <td valign="top" key={i}>
                  <table className="vis" width="100%">
                    <tbody>
                      <tr>
                        <th>{/* Unit Type in original Tribalwars */}</th>
                      </tr>
                      {col.map((id) => {
                        const u = UNIT_BY_ID[id];
                        return (
                          <tr key={id}>
                            <td className="nowrap">
                              <a href="#" className="unit_link" onClick={(e) => popup.open(e, id)}>
                                <img src={`/graphic/unit/unit_${id}.png`} title={u.name} alt="" className="" />
                              </a>{" "}
                              <input
                                id={`unit_input_${id}`}
                                name={id}
                                type="text"
                                style={{ width: 40 }}
                                tabIndex="1"
                                value={counts[id] ?? ""}
                                className="unitsInput"
                                onChange={(e) => setUnit(id, e.target.value)}
                              />{" "}
                              <a href="#" onClick={(e) => insertUnit(e, id, home[id])}>
                                {`(${home[id]})`}
                              </a>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </td>
              ))}
            </tr>
          </tbody>
        </table>

        <input type="text" name="x" id="inputx" value="" style={{ display: "none" }} readOnly />
        <input type="text" name="y" id="inputy" value="" style={{ display: "none" }} readOnly />

        <br />

        {target}

        <div className="target-select clearfix vis float_left">
          <h4>Command:</h4>

          <table className="vis" style={{ width: "100%" }}>
            <tbody>
              <tr>
                <td>
                  <input
                    id="target_attack"
                    tabIndex="15"
                    className="attack btn btn-attack btn-target-action"
                    name="attack"
                    type="submit"
                    value="Attack"
                    onClick={() => (clicked.current = "attack")}
                  />
                  <input
                    id="target_support"
                    tabIndex="16"
                    className="support btn btn-support btn-target-action"
                    name="support"
                    type="submit"
                    value="Support"
                    onClick={() => (clicked.current = "support")}
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </form>
      <div className="vis float_left" ref={rawStyle("margin: 4px 0 0 10px; min-width: 125px;")}>
        <h4>
          <a href={hashOf("place:templates")} id="templates_link" onClick={(e) => go(e, "place:templates")}>Troop template</a>
        </h4>
        <table className="vis" style={{ width: "100%" }}>
          <tbody>
            {troopTemplates.map((t, i) => (
              <tr key={t.name} className={i % 2 ? "row_b" : "row_a"}>
                <td>
                  <a
                    href="#"
                    className="troop_template"
                    onClick={(e) => {
                      e.preventDefault();
                      setSelectAll(false);
                      setCounts(templateCounts(t, home));
                    }}
                  >
                    {t.name}
                  </a>
                </td>
              </tr>
            ))}
            <tr className="row_b">
              <td>
                <a id="selectAllUnits" href="#" onClick={doSelectAll}>
                  All troops
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div className="popup_helper">
        <div id="inline_popup" className="hidden">
          <div id="inline_popup_menu">
            <span id="inline_popup_title" />
            <a id="inline_popup_close" href="#" onClick={(e) => e.preventDefault()}>X</a>
          </div>
          <div id="inline_popup_main" style={{ height: "auto" }}>
            <h3>Ziu</h3>
            <div>
              <div id="inline_popup_content" style={{ height: 340, overflow: "auto" }}>
                <img src="/graphic/throbber.gif" alt="Ladt" />
              </div>
            </div>
          </div>
        </div>
      </div>
      {/* TODO */}
      <h3 style={{ clear: "both" }}>Troop Movements</h3>
      <CommandTables village={village} go={go} onCancel={onCancelCommand} onRename={onRenameCommand} />
    </>
  );
}

/* ---------------- confirmation step (command_confirm.php) ---------------- */
export function CommandConfirm({ target, units, isAttack, duration, morale = 100, now, busy, onSend, go }) {
  const attackName = isAttack ? `Confirm attack on ${target.name}` : `Confirm support for ${target.name}`;
  const capacity = UNIT_LIST.reduce((s, u) => s + (units[u.id] || 0) * u.carry, 0);
  const arrival = fmtOnTime(now + duration * 1000);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(attackName);
  const [draft, setDraft] = useState("");
  const [sent, setSent] = useState(false);
  const [aim, setAim] = useState(""); // the building the catapults aim at ("" = random), as on the original's confirm page
  // #118: an attack on a computer player you are not at war with declares war on it (and its tribe) when it is sent
  const [ownerProfile, setOwnerProfile] = useState(null);
  const npcOwner = isAttack && target.ownerType === "PLAYER" && target.ownerNpc ? target.ownerName : null;
  useEffect(() => {
    if (!npcOwner) return undefined;
    let alive = true;
    api.player(npcOwner).then((p) => alive && setOwnerProfile({ owner: npcOwner, p })).catch(() => {});
    return () => { alive = false; };
  }, [npcOwner]);
  const declares = ownerProfile && ownerProfile.owner === npcOwner && ownerProfile.p.canDeclareWar ? ownerProfile.p : null;
  const grouped = (n) => {
    const s = String(n);
    return s.length <= 3 ? [s] : [s.slice(0, -3), <span className="grey" key="g">.</span>, s.slice(-3)];
  };
  return (
    <form
      id="command-confirm-form"
      method="post"
      onSubmit={(e) => {
        e.preventDefault();
        if (sent) return;
        setSent(true);
        onSend(aim, name !== attackName ? name : null);
      }}
    >
      <input type="hidden" name={isAttack ? "attack" : "support"} value="true" readOnly />

      <h2>{attackName}</h2>

      <input type="hidden" name="ch" value="13b1cd38b7f677813d3bddb0505afce64d3ce4d9" readOnly />
      <input type="hidden" name="x" value={target.x} readOnly />
      <input type="hidden" name="y" value={target.y} readOnly />
      <input type="hidden" name="action_id" value="4165" readOnly />

      <table className="vis" width="300">
        <tbody>
          <tr>
            <th colSpan="2">
              <span id="default_name_span" style={{ display: editing ? "none" : "inline" }}>
                <span id="default_name">{name}</span>{" "}
                <a
                  href="#"
                  onClick={(e) => {
                    e.preventDefault();
                    setDraft(name);
                    setEditing(true);
                  }}
                >
                  <img alt="Rename" src="/graphic/rename.png?1" title="Rename" />
                </a>
              </span>{" "}
              <span id="edit_name" style={{ display: editing ? "inline" : "none" }}>
                <input
                  id="new_attack_name"
                  type="text"
                  maxLength={COMMAND_LABEL_MAX}
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      document.getElementById("attack_name_btn").click();
                    }
                  }}
                />
                <input
                  id="attack_name_btn"
                  type="button"
                  value="Ok"
                  onClick={() => {
                    if (draft) setName(draft);
                    setEditing(false);
                  }}
                />
              </span>{" "}
              <input id="attack_name" name="attack_name" type="hidden" value={name} readOnly />
            </th>
          </tr>

          <tr>
            <td>Target:</td>

            <td>
              <span className="village_anchor contexted" data-id={target.id} data-player="0">
                <a href={hashOf("info_village:" + target.id)} onClick={(e) => navigateTo(e, "info_village:" + target.id, go)}>
                  {`${target.name} (${target.x}|${target.y}) ${continentOf(target)}`}
                </a>{" "}
                <VillageContext village={target} go={go} />
              </span>
            </td>
          </tr>

          <tr>
            <td>Duration:</td>
            <td>{fmtBuildTime(duration * 1000)}</td>
          </tr>

          <tr>
            <td>Arrival:</td>

            <td id="date_arrival">
              <span className="relative_time" data-duration={duration}>
                {arrival}
              </span>
            </td>
          </tr>

          {isAttack && (
            <tr>
              <td>Morale:</td>
              <td style={{ fontWeight: "bold" }}>{`${morale}%`}</td>
            </tr>
          )}

          {isAttack && units.catapult > 0 && (
            <tr>
              <td className="nowrap">
                <label htmlFor="building">Catapults aim at:</label>
              </td>
              <td>
                <select id="building" name="building" value={aim} onChange={(e) => setAim(e.target.value)}>
                  <option value="">Random building</option>
                  {Object.values(B)
                    .filter((b) => b.type)
                    .map((b) => (
                      <option key={b.type} value={b.type}>
                        {b.name}
                      </option>
                    ))}
                </select>
              </td>
            </tr>
          )}

          {capacity > 0 && (
            <tr>
              <td colSpan="2">
                <span className="icon header ressources" title="Carrying capacity" />
                {grouped(capacity)}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {declares && (
        <p className="warn" style={{ fontWeight: "bold" }}>
          Sending this attack declares war on {declares.name}{declares.tribeName ? ` (and its tribe ${declares.tribeName})` : ""}.
        </p>
      )}
      <br />

      <table className="vis">
        <tbody>
          <tr>
            {UNIT_LIST.map((u) => (
              <th width="50" key={u.id}>
                <img alt="" className="" src={`/graphic/unit/unit_${u.id}.png`} title={u.name} />
              </th>
            ))}
          </tr>

          <tr>
            {UNIT_LIST.map((u) => (
              <td key={u.id} className={"unit-item" + (!units[u.id] ? " hidden" : "")}>
                {units[u.id] || 0}
              </td>
            ))}
          </tr>
        </tbody>
      </table>

      <br />

      {UNIT_LIST.map((u) => (
        <input key={u.id} type="hidden" name={u.id} value={units[u.id] || 0} readOnly />
      ))}
      <input
        id="troop_confirm_go"
        className={"btn " + (isAttack ? "btn-attack" : "btn-support")}
        name="submit"
        type="submit"
        value={isAttack ? "Attack" : "Support"}
        disabled={sent || busy}
        autoFocus
      />
    </form>
  );
}

/* ---------------- troops mode (units.php) ---------------- */
export function UnitsMode({ home, village, onWithdraw, onSendBack, go }) {
  const guests = village.stationed ?? []; // support stationed here (tribe-mates or our own other villages)
  const away = village.supporting ?? []; // our troops stationed in other villages (any village, barbarian ones too)
  const [pickGuests, setPickGuests] = useState({});
  const [pickAway, setPickAway] = useState({});
  const [some, setSome] = useState(null); // { id, counts } while choosing how many to withdraw
  const total = { ...home };
  for (const g of guests) for (const [type, n] of Object.entries(g.units)) total[TYPE_TO_ID[type]] = (total[TYPE_TO_ID[type]] ?? 0) + n;
  const cell = (id, n, key) => (
    <td style={{ textAlign: "center" }} className={"unit-item" + (!n ? " hidden" : "")} key={key}>
      {n}
    </td>
  );
  const armyUnits = (army) => Object.fromEntries(Object.entries(army.units).map(([type, n]) => [TYPE_TO_ID[type], n]));
  const villageLink = (a) => (
    <a href={hashOf("map")} onClick={(e) => navigateTo(e, "map", go)}>
      {villageDisplayName({ name: a.villageName, x: a.x, y: a.y })}
    </a>
  );
  const runFor = async (picked, fn) => {
    for (const id of Object.keys(picked).filter((k) => picked[k])) await fn(Number(id));
  };
  const sendSome = async (e) => {
    e.preventDefault();
    const units = {};
    for (const [id, n] of Object.entries(some.counts)) if (n > 0) units[ID_TO_TYPE[id]] = n;
    if (Object.keys(units).length) await onWithdraw(some.id, units);
    setSome(null);
  };

  return (
    <>
      <h3>Troops</h3>
      <form method="post" onSubmit={(e) => { e.preventDefault(); runFor(pickGuests, onSendBack).then(() => setPickGuests({})); }}>
        <table id="units_home" className="vis" width="100%">
          <tbody>
            <tr>
              <th>origin</th>
              {UNIT_LIST.map((u) => (
                <th style={{ textAlign: "center" }} width="40" key={u.id}>
                  <img src={`/graphic/unit/unit_${u.id}.png`} title={u.name} alt="" className="" />
                </th>
              ))}
            </tr>
            <tr>
              <td>From this village</td>
              {UNIT_LIST.map((u) => cell(u.id, home[u.id], u.id))}
            </tr>
            {guests.map((g) => {
              const units = armyUnits(g);
              return (
                <tr key={g.id}>
                  <td>
                    <input type="checkbox" checked={!!pickGuests[g.id]} onChange={(e) => setPickGuests({ ...pickGuests, [g.id]: e.target.checked })} />
                    {villageLink(g)}
                  </td>
                  {UNIT_LIST.map((u) => cell(u.id, units[u.id], u.id))}
                </tr>
              );
            })}
            <tr>
              <th>Overall</th>
              {UNIT_LIST.map((u) => (
                <th style={{ textAlign: "center" }} className={"unit-item" + (!total[u.id] ? " hidden" : "")} key={u.id}>
                  {total[u.id]}
                </th>
              ))}
            </tr>
          </tbody>
        </table>
        {guests.length > 0 && (
          <table align="left">
            <tbody>
              <tr>
                <td><input className="btn" type="submit" name="back" value="Send back" /></td>
              </tr>
            </tbody>
          </table>
        )}
      </form>
      <br style={{ clear: "both" }} />

      <h3>Troops in other villages</h3>
      <form method="post" onSubmit={(e) => { e.preventDefault(); runFor(pickAway, (id) => onWithdraw(id, null)).then(() => setPickAway({})); }}>
        <table id="units_away" className="vis groupcols">
          <tbody>
            <tr>
              <th />
              <th width="320">Village</th>
              {UNIT_LIST.map((u) => (
                <th style={{ textAlign: "center" }} width="auto" key={u.id}>
                  <img src={`/graphic/unit/unit_${u.id}.png`} title={u.name} alt="" className="" />
                </th>
              ))}
              <th>Withdraw</th>
            </tr>
            {away.map((a) => {
              const units = armyUnits(a);
              return (
                <tr key={a.id}>
                  <td>
                    <input type="checkbox" name="withdraw_unit[]" checked={!!pickAway[a.id]} onChange={(e) => setPickAway({ ...pickAway, [a.id]: e.target.checked })} />
                  </td>
                  <td>
                    <span className="village_anchor contexted">{villageLink(a)}</span>
                  </td>
                  {UNIT_LIST.map((u) => cell(u.id, units[u.id], u.id))}
                  <td>
                    <a href="#" onClick={(e) => { e.preventDefault(); setSome({ id: a.id, counts: Object.fromEntries(UNIT_LIST.map((u) => [u.id, 0])), max: units }); }}>Some</a>
                    {" - "}
                    <a href="#" onClick={(e) => { e.preventDefault(); onWithdraw(a.id, null); }}>all</a>
                  </td>
                </tr>
              );
            })}
            <tr />
            <tr>
              <th colSpan={UNIT_LIST.length + 2}>
                <input
                  type="checkbox"
                  id="select_all"
                  className="selectAll"
                  onChange={(e) => setPickAway(e.target.checked ? Object.fromEntries(away.map((a) => [a.id, true])) : {})}
                />
                <label htmlFor="select_all">select all</label>
              </th>
              <th>
                <input className="btn" type="submit" value="Withdraw" />
              </th>
            </tr>
          </tbody>
        </table>
      </form>
      {some && (
        <form method="post" onSubmit={sendSome}>
          <table className="vis">
            <tbody>
              <tr>
                {UNIT_LIST.filter((u) => some.max[u.id]).map((u) => (
                  <th key={u.id}><img src={`/graphic/unit/unit_${u.id}.png`} title={u.name} alt="" /></th>
                ))}
                <th />
              </tr>
              <tr>
                {UNIT_LIST.filter((u) => some.max[u.id]).map((u) => (
                  <td key={u.id}>
                    <input type="number" min="0" max={some.max[u.id]} style={{ width: 50 }} value={some.counts[u.id]}
                      onChange={(e) => setSome({ ...some, counts: { ...some.counts, [u.id]: Math.max(0, Math.min(some.max[u.id], parseInt(e.target.value, 10) || 0)) } })} />
                  </td>
                ))}
                <td>
                  <input className="btn" type="submit" value="Withdraw" />{" "}
                  <a href="#" onClick={(e) => { e.preventDefault(); setSome(null); }}>Cancel</a>
                </td>
              </tr>
            </tbody>
          </table>
        </form>
      )}
    </>
  );
}

/* ---------------- simulator (sim.php + sim_result.php) ---------------- */
const KNIGHT_ITEMS = [
  ["spear", "Halberd of Guan Yu"],
  ["sword", "Paracelsus' Longsword"],
  ["axe", "Thorgard's battle axe"],
  ["archer", "Nimrod's long-bow"],
  ["spy", "Kalid's telescope"],
  ["light", "Mieszko's lance"],
  ["heavy", "Baptiste's Banner"],
  ["marcher", "Nimrod's composite bow"],
  ["ram", "Carol's morning star"],
  ["catapult", "Aletheia's Bonfire"],
  ["snob", "Vasco's Scepter"],
];

function SimResult({ result }) {
  return (
    <>
      <table className="vis">
        <tbody>
          <tr>
            <td colSpan="2" />
            {UNIT_LIST.map((u) => (
              <th width="35" key={u.id}>
                <img alt="" className="" src={`/graphic/unit/unit_${u.id}.png`} title={u.name} />
              </th>
            ))}
          </tr>
          {[
            ["attackingArmy", "Attacker"],
            ["defendingArmy", "Defender"],
          ].map(([key, label]) => (
            <SimArmyRows key={key} label={label} army={result[key]} />
          ))}
          <tr>
            <td style={{ display: "none" }} />
          </tr>
        </tbody>
      </table>

      <table />
    </>
  );
}

function SimArmyRows({ label, army }) {
  return (
    <>
      <tr>
        <td rowSpan="2">{label}</td>
        <td>Unit:</td>
        {UNIT_LIST.map((u) => (
          <td className={"unit-item" + (army.before[u.id] === 0 ? " hidden" : "")} key={u.id}>
            {army.before[u.id]}
          </td>
        ))}
      </tr>
      <tr>
        <td>Casualties:</td>
        {UNIT_LIST.map((u) => {
          const loss = army.before[u.id] - army.after[u.id];
          return (
            <td className={"unit-item" + (loss === 0 ? " hidden" : "")} key={u.id}>
              {loss}
            </td>
          );
        })}
      </tr>
    </>
  );
}

// "» Morale calculation": the morale an attack fights with, from both players' points (BattleCalculator.morale)
function MoraleCalc({ village, onUse }) {
  const own = (village?.myVillages ?? []).reduce((sum, v) => sum + (v.points ?? 0), 0);
  const [att, setAtt] = useState(String(own || ""));
  const [def, setDef] = useState("");
  if (village && !village.moraleOn) return <span id="morale_calc">Morale is off in this world: every attack fights at 100%.</span>;
  const value = morale(Number(att) || 0, Number(def) || 0);
  return (
    <span id="morale_calc">
      <label>
        Your points <input id="morale_att_points" type="text" style={{ width: 60 }} value={att} onChange={(e) => setAtt(e.target.value)} />
      </label>{" "}
      <label>
        Defender's points <input id="morale_def_points" type="text" style={{ width: 60 }} value={def} onChange={(e) => setDef(e.target.value)} />
      </label>{" "}
      = <b>{value}%</b>{" "}
      <input type="button" className="btn" value="Use" onClick={() => onUse(String(value))} />
    </span>
  );
}

export function SimMode({ popup, village }) {
  const run = (form) => {
    const att = {};
    const def = {};
    for (const u of UNIT_LIST) {
      att[u.id] = form["att_" + u.id];
      def[u.id] = form["def_" + u.id];
    }
    return simulate({
      att,
      def,
      wall: form.def_wall,
      moral: form.moral,
      luck: form.luck,
      night: !!form.night,
      beliefAtt: !!form.belief_att,
      beliefDef: !!form.belief_def,
      basicDefense: village?.basicDefense,
      nightBonus: village?.nightBonus,
      destroyBuildings: village?.destroyBuildings,
    });
  };
  // a report's "» Insert troops into the simulator" leaves the battle here (lib/simPreset.js)
  const [f, setF] = useState(() => peekSimPreset() ?? { moral: "100", luck: "0" });
  useEffect(() => clearSimPreset(), []);
  // a battle handed over from a report is calculated at once
  const [result, setResult] = useState(() => (peekSimPreset() ? run(peekSimPreset()) : null));
  const [moraleCalc, setMoraleCalc] = useState(false);
  const set = (k, v) => setF((s) => ({ ...s, [k]: v }));
  const resetSide = (prefix) =>
    setF((s) => {
      const n = { ...s };
      for (const u of UNIT_LIST) delete n[prefix + u.id];
      return n;
    });
  // {unitId: n} into one side of the form (empty fields for 0)
  const fillSide = (prefix, units, extra = {}) =>
    setF((s) => {
      const n = { ...s, ...extra };
      for (const u of UNIT_LIST) n[prefix + u.id] = units[u.id] > 0 ? String(units[u.id]) : "";
      return n;
    });
  const insertHome = (e) => {
    e.preventDefault();
    fillSide("att_", unitsAtHome(village));
  };
  // what defends this village now: its own troops at home, the support stationed here and its wall
  const insertDefence = (e) => {
    e.preventDefault();
    const units = unitsAtHome(village);
    for (const army of village.stationed ?? []) {
      for (const [type, n] of Object.entries(army.units ?? {})) if (TYPE_TO_ID[type]) units[TYPE_TO_ID[type]] += n;
    }
    fillSide("def_", units, { def_wall: String(village.buildings?.find((b) => b.type === "WALL")?.level ?? 0) });
  };
  return (
    <>
      {result && <SimResult result={result} />}
      <h3>Simulator</h3>

      <form
        method="post"
        name="simulator"
        onSubmit={(e) => {
          e.preventDefault();
          setResult(run(f));
        }}
      >
        <input name="simulate" type="hidden" />

        <table className="vis" id="simulator_units_table">
          <tbody>
            <tr>
              <th />
              <th>Attacker</th>
              <th>Defender</th>
            </tr>

            <tr>
              <td />
              <td>Unit</td>
              <td>Unit</td>
            </tr>

            {UNIT_LIST.map((u) => (
              <tr key={u.id}>
                <td>
                  <a className="unit_link" href="#" onClick={(e) => popup.open(e, u.id)}>
                    <img alt="" className="" src={`/graphic/unit/unit_${u.id}.png`} title="" /> {u.name}
                  </a>
                </td>
                <td>
                  <input
                    name={`att_${u.id}`}
                    style={{ width: 50 }}
                    type="text"
                    value={f["att_" + u.id] ?? ""}
                    onChange={(e) => set("att_" + u.id, e.target.value)}
                  />
                </td>
                <td>
                  <input
                    name={`def_${u.id}`}
                    style={{ width: 50 }}
                    type="text"
                    value={f["def_" + u.id] ?? ""}
                    onChange={(e) => set("def_" + u.id, e.target.value)}
                  />
                </td>
              </tr>
            ))}

            {village && (
              <tr>
                <td />
                <td>
                  <a href="#" id="sim_insert_home" onClick={insertHome}>» Insert troops at home</a>
                </td>
                <td>
                  <a href="#" id="sim_insert_defence" onClick={insertDefence}>» Insert this village's defence</a>
                </td>
              </tr>
            )}

            <tr>
              <td />

              <td>
                <input type="button" value="Reset attacker" onClick={() => resetSide("att_")} />
              </td>

              <td>
                <input type="button" value="Reset defender" onClick={() => resetSide("def_")} />
              </td>
            </tr>

            <tr>
              <td />
              <td>
                <label>
                  <input name="belief_att" type="checkbox" checked={!!f.belief_att} onChange={(e) => set("belief_att", e.target.checked)} />
                  Religious
                </label>
              </td>
              <td>
                <label>
                  <input name="belief_def" type="checkbox" checked={!!f.belief_def} onChange={(e) => set("belief_def", e.target.checked)} />
                  Religious
                </label>
              </td>
            </tr>

            <tr>
              <td>Wall</td>
              <td />
              <td colSpan="2">
                <input name="def_wall" style={{ width: 50 }} type="text" value={f.def_wall ?? ""} onChange={(e) => set("def_wall", e.target.value)} />
              </td>
            </tr>

            <tr>
              <td>Items</td>

              <td>
                <select multiple="multiple" name="att_knight_items[]" size="6" defaultValue={[]}>
                  <option value="0">No item</option>
                  {KNIGHT_ITEMS.map(([v, label]) => (
                    <option key={v} value={v}>
                      {label}
                    </option>
                  ))}
                </select>
              </td>

              <td colSpan="2">
                <select multiple="multiple" name="def_knight_items[]" size="6" defaultValue={[]}>
                  <option value="none">No item</option>
                  {KNIGHT_ITEMS.map(([v, label]) => (
                    <option key={v} value={v}>
                      {label}
                    </option>
                  ))}
                </select>
              </td>
            </tr>

            <tr>
              <td>Level of catapult target</td>
              <td />
              <td colSpan="2">
                <input name="def_building" style={{ width: 50 }} type="text" value={f.def_building ?? ""} onChange={(e) => set("def_building", e.target.value)} />{" "}
                <input id="is_church" name="building" type="checkbox" value="church" checked={!!f.building} onChange={(e) => set("building", e.target.checked)} />{" "}
                <label htmlFor="is_church">Church</label>
              </td>
            </tr>

            <tr>
              <td>Morale</td>

              <td colSpan="2">
                <input id="moral" name="moral" style={{ width: 50 }} type="text" value={f.moral} onChange={(e) => set("moral", e.target.value)} />%{" "}
                <a
                  href="#"
                  id="morale_calc_link"
                  onClick={(e) => {
                    e.preventDefault();
                    setMoraleCalc((open) => !open);
                  }}
                >
                  » Morale calculation
                </a>
                {moraleCalc && (
                  <>
                    <br />
                    <MoraleCalc
                      village={village}
                      onUse={(v) => {
                        set("moral", v);
                        setMoraleCalc(false);
                      }}
                    />
                  </>
                )}
              </td>
            </tr>

            <tr>
              <td>Night</td>

              <td />

              <td colSpan="2">
                <label>
                  <input name="night" type="checkbox" checked={!!f.night} onChange={(e) => set("night", e.target.checked)} />{" "}
                  {`Night bonus: defence x${village?.nightBonus ?? 2}`}
                </label>
              </td>
            </tr>

            <tr>
              <td>Luck (from negative 25% to positive 25%)</td>
              <td colSpan="2">
                <input name="luck" size="5" type="text" value={f.luck} onChange={(e) => set("luck", e.target.value)} />%
                <br />
                <span className="grey">A real battle rolls its luck at random; its report shows the luck, morale and night bonus it was fought with.</span>
              </td>
            </tr>
          </tbody>
        </table>
        <input className="btn" type="submit" value="Calculate" />
      </form>
    </>
  );
}
