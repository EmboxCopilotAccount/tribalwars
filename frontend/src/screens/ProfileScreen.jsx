// screen=info_player — a player's profile: standing, villages, kills, achievements and their own text.
//   #info_player          your own profile
//   #info_player:p_<name> another player's profile (linked from the ranking, the map, reports)
// Layout follows Tribal Wars' profile page (name header, key facts table, village list, description); the original TWLan
// bundle has no such page, so the tables use its .vis styling.
import { useEffect, useState } from "react";
import { api } from "../api";
import { continentOf, fmtOnTime } from "../lib/military";
import { AwardImage } from "./achievements/AchievementsScreen";
import { hashOf } from "../lib/nav";

const fmt = (n) => Number(n).toLocaleString("de-DE");
const fmtDay = (iso) => (iso ? new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" }) : "");

export const profileTarget = (name) => "info_player:p_" + name;

// How a war with an NPC came about, from the player's side.
function warOrigin(w, go) {
  if (w.viaTribe) {
    return (
      <>
        via the tribe {w.tribeId ? <a href={hashOf("info_ally:" + w.tribeId)} onClick={(e) => go(e, "info_ally:" + w.tribeId)}>{w.tribeName}</a> : w.tribeName}: declared
        on <a href={hashOf(profileTarget(w.declaredOn))} onClick={(e) => go(e, profileTarget(w.declaredOn))}>{w.declaredOn}</a>, peace is made with them
      </>
    );
  }
  return w.declaredBy === "PLAYER" ? "declared by you" : "declared by them after your attack";
}

const peaceOpen = (w) => new Date(w.peaceFrom) <= new Date();

export function ProfileScreen({ name, go }) {
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);
  const [actionError, setActionError] = useState(null);

  useEffect(() => {
    let alive = true;
    setProfile(null);
    setError(null);
    setActionError(null);
    const load = () => api.player(name ?? "me").then((p) => alive && setProfile(p)).catch((e) => alive && setError(e.message));
    load();
    const id = setInterval(load, 10000);
    return () => { alive = false; clearInterval(id); };
  }, [name]);

  if (error) return <div className="error_box">{error}</div>;
  if (!profile) return <p>Loading…</p>;

  const p = profile;
  const war = p.war;

  const act = (call) => call(p.name).then(setProfile).then(() => setActionError(null)).catch((e) => setActionError(e.message));
  const declareWar = (e) => {
    e.preventDefault();
    const hours = p.warPeaceHours;
    const text = !p.npcsAttack
      ? `Declare war on ${p.name}?\n\nNPCs never attack on this world, so for now the war changes nothing for you.`
      : `Declare war on ${p.name}?\n\n${p.name}${p.tribeName ? ` and the other computer players of its tribe ${p.tribeName}` : ""} will scout and attack your villages, `
        + `and may take them with noblemen. Peace is possible ${hours > 0 ? `only after ${hours} ${hours === 1 ? "hour" : "hours"}` : "at any time"}.`;
    if (window.confirm(text)) act(api.declareWar);
  };
  const makePeace = (e) => {
    e.preventDefault();
    if (window.confirm(`Make peace with ${p.name}?`)) act(api.makePeace);
  };
  const paragraphs = (p.description || "").split(/\n/);
  return (
    <>
      <h2>{p.name}</h2>
      <table width="100%">
        <tbody>
          <tr>
            <td valign="top" width="320">
              <table className="vis" width="100%">
                <tbody>
                  <tr>
                    <th colSpan="2">Player</th>
                  </tr>
                  <tr><td width="110">Name:</td><td>{p.name}</td></tr>
                  <tr><td>Rank:</td><td>{p.rank} of {p.players}</td></tr>
                  <tr><td>Points:</td><td>{fmt(p.points)}</td></tr>
                  <tr>
                    <td>Tribe:</td>
                    <td>
                      {p.tribeId ? (
                        <>
                          <a href={hashOf("info_ally:" + p.tribeId)} onClick={(e) => go(e, "info_ally:" + p.tribeId)}>{p.tribeName}</a>
                          {p.tribeTitle ? ` (${p.tribeTitle})` : ""}
                        </>
                      ) : "—"}
                    </td>
                  </tr>
                  <tr><td>Villages:</td><td>{(p.villages ?? []).length}</td></tr>
                  <tr><td>Opponents defeated:</td><td>{fmt(p.kills)}</td></tr>
                  <tr><td>Player since:</td><td>{fmtDay(p.memberSince)}</td></tr>
                  {p.birthday && <tr><td>Birthday:</td><td>{fmtDay(p.birthday)}</td></tr>}
                  {p.location && <tr><td>Location:</td><td>{p.location}</td></tr>}
                  {war && (
                    <tr>
                      <td>War:</td>
                      <td>
                        <span className="warn">At war</span> since {fmtOnTime(war.startedAt, false)} ({warOrigin(war, go)})
                        {war.pending && <div className="inactive">Cancelling your attack in time calls this war off.</div>}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
              <br />
              <table className="vis" width="100%">
                <tbody>
                  <tr>
                    <th>Villages</th>
                    <th>Coordinates</th>
                    <th>Points</th>
                  </tr>
                  {(p.villages ?? []).map((v) => (
                    <tr key={v.id}>
                      <td><a href={hashOf("info_village:" + v.id)} onClick={(e) => go(e, "info_village:" + v.id)}>{v.name}</a></td>
                      <td>{`${v.x}|${v.y} ${continentOf(v)}`}</td>
                      <td>{fmt(v.points)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p>
                <a href={hashOf(p.own ? "info_player:stats_own" : "info_player:s_" + p.name)} onClick={(e) => go(e, p.own ? "info_player:stats_own" : "info_player:s_" + p.name)}>» Statistics</a>
                {!p.own && (
                  <>
                    <br />
                    <a href={hashOf("mail:new_" + p.name)} onClick={(e) => go(e, "mail:new_" + p.name)}>» Send message</a>
                  </>
                )}
                {p.own && (
                  <>
                    <br />
                    <a href={hashOf("settings:profile")} onClick={(e) => go(e, "settings:profile")}>» Edit profile</a>
                  </>
                )}
                {p.canDeclareWar && (
                  <>
                    <br />
                    <a href="#" onClick={declareWar}>» Declare war</a>
                  </>
                )}
                {war && !war.viaTribe && (
                  <>
                    <br />
                    {peaceOpen(war)
                      ? <a href="#" onClick={makePeace}>» Make peace</a>
                      : <span className="inactive">» Make peace (possible from {fmtOnTime(war.peaceFrom, false)})</span>}
                  </>
                )}
              </p>
              {actionError && <div className="error_box">{actionError}</div>}
              {p.own && (p.wars ?? []).length > 0 && (
                <table className="vis" width="100%">
                  <tbody>
                    <tr>
                      <th>Wars</th>
                      <th>Since</th>
                      <th>Peace</th>
                    </tr>
                    {p.wars.map((w) => (
                      <tr key={w.opponent}>
                        <td>
                          <a href={hashOf(profileTarget(w.opponent))} onClick={(e) => go(e, profileTarget(w.opponent))}>{w.opponent}</a>
                          {w.tribeName ? ` (${w.tribeName})` : ""}
                        </td>
                        <td>{fmtOnTime(w.startedAt, false)}</td>
                        <td>{peaceOpen(w) ? "possible" : <span className="inactive">from {fmtOnTime(w.peaceFrom, false)}</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </td>
            <td valign="top">
              <table className="vis" width="100%">
                <tbody>
                  <tr>
                    <th>Profile text</th>
                  </tr>
                  <tr>
                    <td>
                      {p.description ? paragraphs.map((line, i) => <div key={i}>{line || " "}</div>) : <span className="inactive">{p.own ? "You have not written anything about yourself yet." : "This player has not written anything yet."}</span>}
                    </td>
                  </tr>
                </tbody>
              </table>
              <br />
              <table className="vis" width="100%">
                <tbody>
                  <tr>
                    <th>Achievements ({p.achievements.reduce((n, a) => n + a.level, 0)} levels)</th>
                  </tr>
                  <tr>
                    <td>
                      {p.achievements.length === 0 && <span className="inactive">No achievements earned yet.</span>}
                      {p.achievements.map((a) => (
                        <span key={a.key} title={`${a.name} (level ${a.level}/${a.maxLevel})`} style={{ display: "inline-block", marginRight: 4, cursor: "pointer" }}
                          onClick={(e) => p.own && go(e, "info_player:awards")}>
                          <AwardImage icon={a.icon} level={a.level} />
                        </span>
                      ))}
                      <div style={{ clear: "both" }} />
                    </td>
                  </tr>
                  {p.own && (
                    <tr>
                      <td><a href={hashOf("info_player:awards")} onClick={(e) => go(e, "info_player:awards")}>» All achievements</a></td>
                    </tr>
                  )}
                </tbody>
              </table>
            </td>
          </tr>
        </tbody>
      </table>
    </>
  );
}
