// The small arrow after a village link (".village_anchor > .ctx", original: UI.VillageContext): clicking it opens a
// ring of the map's context icons (#ctx_* sprites in game.css) around the arrow. Village information, send troops,
// send resources, centre the map, add to / remove from favourites (the map's list). Click elsewhere or Escape closes it.
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { loadSetting, saveSetting } from "../lib/map";
import { navigateTo } from "../lib/military";

// offsets of the 24px icons from the arrow's centre: top, upper right, lower right, bottom, lower left
const RING = [[-12, -46], [18, -28], [18, 4], [-12, 22], [-42, 4], [-42, -28]];

function isFavorite(id) {
  const list = loadSetting("favorites", []);
  return Array.isArray(list) && list.includes(id);
}
function toggleFavorite(id) {
  const list = loadSetting("favorites", []);
  const ids = Array.isArray(list) ? list : [];
  saveSetting("favorites", ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);
}

/** village: {id, x, y} (null when the village is gone: the arrow does nothing); currentId: the village being played */
export function VillageContext({ village, currentId, go }) {
  const [at, setAt] = useState(null); // page coordinates of the arrow's centre while the ring is open
  const arrow = useRef(null);

  useEffect(() => {
    if (!at) return;
    const close = (e) => {
      // a click on this arrow toggles in open(); anywhere else but the ring closes it
      if (e.type === "keydown" ? e.key === "Escape" : !e.target.closest?.(".village_ctx") && !arrow.current?.contains(e.target)) setAt(null);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [at]);

  const open = (e) => {
    e.preventDefault();
    if (!village?.id) return;
    if (at) return setAt(null);
    const r = e.currentTarget.getBoundingClientRect();
    setAt({ x: r.left + r.width / 2 + window.scrollX, y: r.top + r.height / 2 + window.scrollY });
  };

  const fav = at && village?.id ? isFavorite(village.id) : false;
  const own = village?.id === currentId;
  const buttons = [
    ["ctx_info", "Village information", (e) => navigateTo(e, "info_village:" + village.id, go)],
    !own && ["ctx_place", "Send troops", (e) => navigateTo(e, "place:" + village.id, go)],
    !own && ["ctx_market", "Send resources", (e) => navigateTo(e, "market:" + village.id, go)],
    village?.x != null && ["ctx_map", "Centre the map on this village", (e) => navigateTo(e, `map:${village.x},${village.y}`, go)],
    [fav ? "ctx_unfav" : "ctx_fav", fav ? "Remove from favourites" : "Add to favourites", (e) => {
      e.preventDefault();
      toggleFavorite(village.id);
    }],
  ].filter(Boolean);

  return (
    <>
      <a className="ctx" href="#" title="More" ref={arrow} onClick={open} />
      {at &&
        createPortal(
          buttons.map(([id, title, act], i) => (
            <a
              key={id}
              id={id}
              className="village_ctx"
              href="#"
              title={title}
              style={{ left: at.x + RING[i][0], top: at.y + RING[i][1] }}
              onClick={(e) => {
                setAt(null);
                act(e);
              }}
            />
          )),
          document.body,
        )}
    </>
  );
}
