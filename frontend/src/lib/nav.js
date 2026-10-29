/* In-game links: the App keeps its screen key ("info_player:p_name") in the URL hash, so every link carries its real
   target as href. A plain click navigates inside the app; ctrl/shift/cmd-click and middle-click are left to the
   browser, which opens the href in a new tab (middle-click never fires onClick, only the href matters there). */

export const hashOf = (target) => "#" + encodeURIComponent(target).replace(/%3A/g, ":");

/** href of a screen in one of the player's own villages: the new tab starts in that village. */
export const villageHref = (villageId, target) => `?village=${villageId}${hashOf(target)}`;

/** true when the browser should handle the click itself (new tab / window) instead of the app. */
export const newTabClick = (e) => !!e && (e.button > 0 || e.ctrlKey || e.metaKey || e.shiftKey);

/** `?village=<id>` of a link opened in a new tab: returns the id and drops the parameter from the address bar. */
export function takeVillageParam() {
  if (typeof window === "undefined") return null;
  const url = new URL(window.location.href);
  const id = Number(url.searchParams.get("village"));
  if (!url.searchParams.has("village")) return null;
  url.searchParams.delete("village");
  window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  return Number.isInteger(id) && id > 0 ? id : null;
}
