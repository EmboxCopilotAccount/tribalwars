#!/usr/bin/env node
// Render every screen key of our App in jsdom (backend on :8080 must be up) and report
// thrown errors / React warnings. Usage: node tools/smoke.mjs
import { JSDOM } from 'jsdom'
import { devSession, seedStorage } from './auth.mjs'
const SCREENS = ['overview', 'building:HEADQUARTERS', 'building:TIMBER_CAMP', 'building:CLAY_PIT', 'building:IRON_MINE', 'building:FARM', 'building:WAREHOUSE', 'building:BARRACKS', 'building:WALL',
  'main', 'wood', 'stone', 'iron', 'storage', 'hide', 'wall', 'statue', 'snob', 'stable', 'garage', 'smith', 'barracks', 'train', 'place', 'place:command', 'place:units', 'place:sim', 'place:secrets', 'place:templates',
  'map', 'report:all', 'report:attack', 'ranking:player', 'ranking:ally', 'ranking:kill_player', 'overview_villages:combined', 'overview_villages:prod', 'overview_villages:groups', 'ally', 'ally:invitations', 'ally:forum', 'ally:forum_b1', 'ally:forum_t1', 'ally:forum_admin', 'ally:wars', 'ranking:wars', 'mail:in', 'mail:new', 'mail:new_someone', 'mail:mass_out', 'mail:address', 'mail:groups', 'mail:block', 'settings:block', 'market:send', 'market:other_offer', 'market:own_offer', 'market:traders', 'overview_villages:trader', 'info_village:2', 'info_command:1', 'place:2', 'map:500,500',
  // added 2026-10-30: modes that had grown since the list was written
  'overview_villages:units', 'overview_villages:commands', 'overview_villages:incomings', 'overview_villages:buildings', 'overview_villages:tech',
  'info_player:stats_own', 'info_player:awards', 'info_player:block', 'settings:profile', 'settings:settings', 'settings:change_passwd',
  'ally:profile', 'ally:members', 'ally:contracts', 'ally:invite', 'ally:properties', 'report:defense', 'place:farm', 'train:decommission',
  'market', 'farm', 'church', 'watchtower', 'info_player', 'info_village', 'info_command', 'info_ally', 'mail', 'settings', 'inventory', 'buddies', 'stat', 'groups', 'nonexistent']
const session = await devSession()
const realFetch = globalThis.fetch
globalThis.fetch = (u, o) => realFetch(String(u).startsWith('/') ? (process.env.TWLAN_BACKEND || 'http://localhost:8080') + u : u, o)
globalThis.IS_REACT_ACT_ENVIRONMENT = true
// react-dom checks for a DOM once, when it is imported: give it a window first, or it falls back to the IE input
// polyfill (attachEvent) and every screen with a focused input crashes
const GLOBALS = ['window', 'document', 'navigator', 'HTMLElement', 'Node', 'MutationObserver', 'localStorage']
function useDom(dom) {
  for (const k of GLOBALS) {
    try { globalThis[k] = dom.window[k] } catch { Object.defineProperty(globalThis, k, { value: dom.window[k], configurable: true }) }
  }
}
useDom(new JSDOM('<!doctype html><body></body>', { url: 'http://localhost:5173/', pretendToBeVisual: true }))
const { createServer } = await import('vite')
const vite = await createServer({ root: process.cwd(), server: { middlewareMode: true, hmr: false }, appType: 'custom', logLevel: 'error' })
const React = await import('react')
const { createRoot } = await import('react-dom/client')
const App = (await vite.ssrLoadModule('/src/App.jsx')).default
const apiModule = await vite.ssrLoadModule('/src/api.js') // its world id is read once at load, so set it per screen
let bad = 0
// SMOKE_ONLY=overview,map: just those screens
const only = process.env.SMOKE_ONLY ? process.env.SMOKE_ONLY.split(',') : null
for (const s of only ?? SCREENS) {
  const dom = new JSDOM('<!doctype html><body id="ds_body"><div id="root"></div></body>', { url: 'http://localhost:5173/#' + encodeURIComponent(s).replace(/%3A/g, ':'), pretendToBeVisual: true })
  seedStorage(dom.window, session)
  useDom(dom)
  if (session) apiModule.setWorldId(session.worldId)
  const errors = []
  console.error = (...a) => errors.push(a.map(String).join(' ').slice(0, 200))
  const onErr = (e) => errors.push('uncaught: ' + (e.message ?? e))
  dom.window.addEventListener('error', (e) => onErr(e.error ?? e))
  const root = createRoot(dom.window.document.getElementById('root'))
  try {
    await React.act(async () => { root.render(React.createElement(App)) })
    for (let i = 0; i < 160 && !dom.window.document.getElementById('content_value') && s !== 'groups'; i++) await React.act(async () => { await new Promise(r => setTimeout(r, 25)) })
    await React.act(async () => { await new Promise(r => setTimeout(r, 200)) })
  } catch (e) { errors.push('THROWN: ' + e.message) }
  const has = !!dom.window.document.getElementById('content_value')
  const ok = errors.length === 0 && (has || s === 'groups')
  if (!ok) bad++
  console.log((ok ? 'ok   ' : 'FAIL ') + s + (has ? '' : ' (no content_value; page: ' + JSON.stringify(dom.window.document.body.textContent.replace(/\s+/g, ' ').trim().slice(0, 120)) + ')') + (errors.length ? '\n     ' + [...new Set(errors)].slice(0, 3).join('\n     ') : ''))
  try { await React.act(async () => root.unmount()) } catch {}
  dom.window.close()
}
console.log(bad ? `${bad} screen(s) with problems` : 'all screens rendered cleanly')
await vite.close(); process.exit(bad ? 1 : 0)
