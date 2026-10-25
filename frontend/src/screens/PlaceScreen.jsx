// screen=place — the rally point. Structure follows templates/twlan/controllers/game/place/*.php:
//   header + navi (Commands | Secret | Troops | Simulator | Troop-Templates)
//   command: form (units, target field, Attack/Support) -> confirm step (try=confirm) -> send
import { useEffect, useState } from "react";
import { PreErrorBox } from "../lib/ui";
import { ID_TO_TYPE, UNIT_LIST, moraleAgainst, travelSeconds, unitsAtHome, useHashNav, useNowTick } from "../lib/military";
import { CommandConfirm, CommandForm, PlaceHeader, SimMode, TargetField, UnitsMode } from "./military/PlaceParts";
import { pushRecent } from "../lib/targets";
import { takeCommandPreset } from "../lib/commandPreset";
import { FarmAssistant } from "./military/FarmAssistant";
import { TroopTemplates } from "./military/TroopTemplates";
import { useUnitPopup } from "./military/UnitPopup";

const MODE_IDS = ["command", "secrets", "units", "sim", "templates", "farm"];

export function PlaceScreen({ village, villages = [], reports = [], onAttack, onSupport, onWithdraw, onSendBack, onCancelCommand, onRenameCommand, busy, go }) {
  const [parts, navigate] = useHashNav("place", go);
  const now = useNowTick();
  const popup = useUnitPopup();

  const mode = MODE_IDS.includes(parts[0]) ? parts[0] : "command";
  const home = unitsAtHome(village);

  // command form state
  const [counts, setCounts] = useState({});
  const [targetType, setTargetType] = useState("coord");
  const [text, setText] = useState("");
  const [confirmedTarget, setConfirmedTarget] = useState(null);
  // "place:<villageId>" (the map's "Attack" / screen=place&target=ID of the original): the village is preselected as target
  const [presetId, setPresetId] = useState(null);
  const wantedId = /^\d+$/.test(parts[0] ?? "") ? Number(parts[0]) : null;
  if (wantedId != null && wantedId !== presetId) {
    const v = villages.find((x) => x.id === wantedId);
    if (v) {
      setPresetId(wantedId);
      setConfirmedTarget(v);
      setText("");
      setTargetType("coord");
    }
  }
  // troops handed over by a report ("» Attack again ..."), once the sending village is the current one
  useEffect(() => {
    const units = takeCommandPreset(village.id);
    if (!units) return;
    const at = unitsAtHome(village);
    const c = {};
    for (const u of UNIT_LIST) {
      const n = units === "all" ? at[u.id] : Math.min(units[u.id] ?? 0, at[u.id]);
      if (n > 0) c[u.id] = String(n);
    }
    setCounts(c);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [village.id]);
  const [error, setError] = useState(null);
  // confirmation step: { target, units, isAttack }
  const [confirm, setConfirm] = useState(null);

  const resolveTarget = () => {
    if (confirmedTarget) return confirmedTarget;
    const m = text.match(/^\s*(\d{1,3})\s*\|\s*(\d{1,3})\s*$/);
    if (m) return villages.find((v) => v.x === Number(m[1]) && v.y === Number(m[2])) ?? "invalid";
    if (text.trim()) {
      const t = text.trim().toLowerCase();
      return villages.find((v) => v.name.toLowerCase() === t) ?? "invalid";
    }
    return null;
  };

  // "try=confirm": validate like the original (units -> availability -> target), then show the confirm page
  const submitCommand = (button) => {
    const units = {};
    let sum = 0;
    let tooMany = false;
    for (const u of UNIT_LIST) {
      const n = parseInt(counts[u.id], 10);
      if (n > 0) {
        units[u.id] = n;
        sum += n;
        if (n > home[u.id]) tooMany = true;
      }
    }
    if (sum === 0) return setError("No units selected");
    if (tooMany) return setError("Not enough units available");
    const target = resolveTarget();
    if (target === null) return setError("You need to enter the x and y coordinates of the destination");
    if (target === "invalid") return setError("Target does not exist");
    const isOwn = (village.myVillages ?? []).some((v) => v.id === target.id);
    if (target.id === village.id) {
      return setError(button === "support" ? "The troops are already in this village" : "Cannot attack your own village");
    }
    // support goes to any village; on worlds set to "tribe" only to the player's own villages and their tribe's
    // (the tribe of a village's owner comes with the village list). Same rule as MovementService.canSupport.
    const myTribe = villages.find((v) => v.id === village.id)?.ownerTribeId;
    const sameTribe = !!myTribe && target.ownerTribeId === myTribe;
    if (button === "support" && !isOwn && village.supportTribeOnly && !sameTribe) return setError("You can only send support to your own villages and to villages of your tribe members");
    if (button === "support" && !isOwn && units.snob > 0) return setError("Noblemen cannot be stationed in another player's village");
    if (button !== "support" && isOwn && !village.attackSelf) return setError("Cannot attack your own village");
    setError(null);
    setConfirm({ target, units, isAttack: button !== "support" });
  };

  const durationSeconds = (c) => travelSeconds(village, c.target, Object.keys(c.units), village.travelSpeed);

  // label: the name typed on the confirm page (null = the default one)
  const send = async (catapultTarget, label) => {
    const units = {};
    for (const [id, n] of Object.entries(confirm.units)) units[ID_TO_TYPE[id]] = n;
    const target = confirm.target;
    const isAttack = confirm.isAttack;
    setConfirm(null);
    setCounts({});
    setText("");
    setConfirmedTarget(null);
    pushRecent(village.worldId, target.id, isAttack);
    await (isAttack ? onAttack(target.id, units, catapultTarget || undefined, label || undefined) : onSupport(target.id, units, label || undefined));
  };

  const errorBox = error && <div className="error_box">{error}</div>;

  if (mode === "secrets") {
    return (
      <>
        <PreErrorBox text="Secrets not implemented" />
        {popup.node}
      </>
    );
  }

  let body;
  if (mode === "command" && confirm) {
    body = (
      <CommandConfirm
        target={confirm.target}
        units={confirm.units}
        isAttack={confirm.isAttack}
        duration={durationSeconds(confirm)}
        morale={moraleAgainst(village, villages, confirm.target)}
        now={now}
        busy={busy}
        onSend={send}
        go={go}
      />
    );
  } else if (mode === "command") {
    body = (
      <CommandForm
        village={village}
        villages={villages}
        go={go}
        home={home}
        counts={counts}
        setCounts={setCounts}
        popup={popup}
        onSubmit={submitCommand}
        onCancelCommand={onCancelCommand}
        onRenameCommand={onRenameCommand}
        target={
          <TargetField
            village={village}
            villages={villages}
            type={targetType}
            setType={setTargetType}
            text={text}
            setText={setText}
            confirmed={confirmedTarget}
            setConfirmed={setConfirmedTarget}
          />
        }
      />
    );
  } else if (mode === "farm") {
    body = <FarmAssistant village={village} villages={villages} reports={reports} home={home} onAttack={onAttack} busy={busy} go={go} />;
  } else if (mode === "templates") {
    body = <TroopTemplates village={village} home={home} />;
  } else if (mode === "units") {
    body = <UnitsMode home={home} village={village} onWithdraw={onWithdraw} onSendBack={onSendBack} go={go} />;
  } else {
    body = <SimMode popup={popup} village={village} />;
  }

  return (
    <>
      {errorBox}
      <PlaceHeader mode={mode} navigate={navigate} />
      {body}
      {popup.node}
    </>
  );
}
