# TWLAN - Tribal Wars Lan / Private Server

A modern, local, clone of **Tribal Wars**, built from scratch with Java and React.

> Not affiliated with or published by InnoGames.

## About this fork

This is an unofficial fork of [wklnd/tribalwars](https://github.com/wklnd/tribalwars) with 116 fixes and additions, found while running a world for a small group of players. Each commit is one change and most come with tests. `./gradlew test` passes, and the frontend builds with no new lint warnings.

**Upgrading an existing world:** back it up first (Admin panel → Backup). Some changes add tables and columns (`player_war`, `map_group`, `map_group_entry`, `village_note`), and a few run one-time fixes on startup.

### Fixes for issues reported upstream

| Issue | Fixed by | Also on its own branch |
|---|---|---|
| [#2](https://github.com/wklnd/tribalwars/issues/2) Not enough resources with a long build queue | build costs include the queue surcharge the server charges | `issue/2-7-farm` |
| [#3](https://github.com/wklnd/tribalwars/issues/3) Missing pages | Farm page and Troop templates added (Settings, Friends and Secrets are still missing) | – |
| [#5](https://github.com/wklnd/tribalwars/issues/5) Commands can't be cancelled or renamed | cancel within the first minutes; rename on the rally point and the village overview | – |
| [#6](https://github.com/wklnd/tribalwars/issues/6) Wrong coordinates in reports and commands | the backend sends each village's coordinates instead of looking them up by name | `issue/6-coordinates` |
| [#7](https://github.com/wklnd/tribalwars/issues/7) Farm too small to upgrade the farm | farm and warehouse stay buildable over the farm limit; population counted correctly | `issue/2-7-farm` |

The `issue/*` branches start directly from upstream `main` and change no tables, so they're the safest to try on their own.

## All changes, by category

### Bug fixes (10)

Things that were broken: combat, conquest, coordinates, logins.

- Show the right village coordinates in reports
- Show the right village coordinates in command lists
- Use one continent label everywhere
- Test that commands and reports carry their own village coordinates
- Combat: an attack between real players where a side loses nothing no longer fails every tick
- Conquest: earlier waves still go home; the old owner's returning troops are lost, not handed over
- Damaged buildings: queued upgrades build on from the damaged level
- Conquest uses up the nobleman that takes the village
- A refused API request (403/423) no longer leaves its login on the server thread for the next request
- Using the login token counts as a visit: players who stay logged in no longer have their villages turned barbarian after leftVillagesGrow

### Gameplay fixes (23)

Smaller rule and display fixes, so the screens and the server agree.

- Farm Assistant: read raids with scouts as battles, wall only as the report shows it
- Reports: show the loot of a won raid that had scouts along
- Farm Assistant: drop the 'sent' marker once the command is under way or reported
- Resolve movements that fall due in the same tick in arrival order
- Farm: troops being dismissed count once, not twice
- Show build costs with the queue surcharge the server charges
- Build screen: 'Farm too small' also counts the farm space queued orders reserve
- Farm counts troops away from home (on the road and stationed as support)
- Wall screen shows the basic defence the battle adds (world setting, not 20 per level)
- Rams lower the wall before the battle (half of them; the damage stays even if the attack fails)
- Building upgrades settle production at the old level first (mines/warehouse no longer pay backwards)
- Attacking your own village fights (attackSelf), instead of moving the troops in
- Spy reports count support stationed elsewhere as troops outside
- Opponents defeated counts for defenders and NPCs too (not for fighting yourself)
- Academy screen uses the world's coin cost (village.nobles) instead of a fixed one
- Queues: the next order starts when the previous one finished, so downtime no longer eats queue time
- 'today'/'tomorrow' labels compare the whole date, not just the day of the month
- Pit and warehouse screens include the bonus village factor at the next level
- Rankings: players and tribes on equal points/kills share a rank (1, 1, 3), like the other pages
- Travel times on screen use the world's unit speed, like the troops do (rally point, map, Farm Assistant)
- Support to your own village stays the sending village's troops and can be withdrawn (NPC dodges still move troops for good)
- Abandoned villages (and villages an admin turns barbarian) lose their market offers
- Review leftovers: a command of the old owner due in the same tick as the conquest is dropped, conquest cancels the research queue, returning loot never lowers a stock above the warehouse

### NPC fixes (6)

Unit speed in their planning, Academy goals, tribe invitations, market fills.

- NPC market: fill only player offers that are fair to the NPC
- NPCs turn down an invitation from a tribe that filled up, instead of stalling every NPC tribe action
- NPC alarm, support, dodge and attack planning use the world's unit speed for travel times
- Expanding raider and balanced NPCs build an Academy too, so they can educate noblemen
- NPCs wake up when their troops get home (return trips are found by their target village)
- NPCs don't save for coins or plan an Academy when noblemen are off on the world

### Original rule values (3)

Morale, bonus villages and recruit times set to the original game's values. These are judgement calls.

- Recruit time follows the original curve (base * 2/3 / 1.06^level)
- Bonus villages use the original values: +30% of all resources, storage bonus also gives 50% more merchants
- Morale uses the original formula: 3 x defender/attacker points + 0.3, capped at 100%

### More information on screens (7)

Screens show what the original shows. The font is self-hosted instead of loaded from Google Fonts.

- Village overview: show when each building's whole queue is done
- Build prices show the queue surcharge as a red (+x%) next to them (HQ table and overview tooltips)
- Morale shows what the battle will use: attack confirm page and map popup (Show morale setting now works)
- Cancelling recruit orders says what comes back (90%) and what is lost, including a full warehouse
- Farm has a page: maximum population now and at the next level (bonus included), farmers in use and free
- Farm Assistant: a Resources column shows what the latest scouting saw there to plunder (hiding place and the scouts' own raid subtracted, grey once attacked since)
- Self-host the MedievalSharp font (SIL OFL) instead of loading it from Google Fonts

### Features (11)

Catapult targeting, cancelling commands, market filters, reordering queues, demolishing, browser Back/Forward.

- Catapults can be aimed at a building on the attack confirm page (default: random, as before)
- Own attacks and support can be cancelled in their first 10 minutes; the troops turn round (rally point command list)
- Cancelling commands: the window is 10 minutes divided by the world speed (at least 1 minute), and the command page has a 'Cancel command' link with the time left
- Reports list can be filtered by dot colour (no losses / won with losses / lost), remembered per browser
- Market Buy page: filter by offered/requested resource and distance, sortable columns, remembered per browser
- Market pages list only the current village's merchants (next to its available count); the villages overview names the own village of each shipment
- Build and recruit queues: waiting orders can be dragged to another place by their handle (levels of the same building renumbered)
- Market Send page: pick one of your other villages from a dropdown as the destination
- Market Send page: the History popup lists the villages the market shipped to (its own list), not the rally point's troop targets; no 'last attacked' button there
- Demolish buildings from headquarters level 15: one level at a time, free, no refund, build time of the level removed; never mixed with upgrades; HQ/farm/warehouse keep level 1 and the farm must still feed the village
- Browser Back/Forward walk through the screens: each new screen is a history entry, and the App follows popstate

### Reports, simulator, overviews and rally point (24)

Missing screens from the original, support to any village (world setting `supportTargets`), and a fix for a database deadlock between tabs.

- Reports show the morale of every battle under the luck bar, like the original
- Reports show the night bonus the defence fought with
- Farm Assistant: templates grow as you use them, up to Z
- Recruit screens count support stationed in other villages in "In the village/total"
- Overview troops widget counts support stationed in the village, with a link to it
- Reports can be deleted on the server
- Support and trade reports, working report tabs
- Overviews: Production, Troops, Commands, Incoming, Buildings, Research
- Village state is read-only: no deadlock between tabs on different villages
- A busy account row no longer fails the request
- Simulator fights like the server
- Simulator: insert the troops at home or this village's defence
- Simulator: "» Morale calculation" works
- Simulator: drop the flag pickers
- Reports: insert the battle into the simulator
- Reports: attack again from a battle report
- Reports: newer/older report within the current tab
- Reports: export a battle report as BB code
- Rally point: troop templates page and template box in the command form
- Rally point: rename commands, keep the confirm page's name
- Village context menu behind the arrow after village links
- Support any player's village, world setting supportTargets keeps the tribe-only rule
- Support can go to any village, barbarian ones included; one rule when sent and on arrival
- Orders cost resources again: toDto saves the village it is given (fixes a regression from the read-only village state change)

### NPC wars, game lock, night bonus (13)

NPCs only attack players at war with them (world settings `warPeaceHours`, `warAttacksPerHour`, `warNoNightAttacks`). One lock for requests, the tick and NPCs, so parallel actions can't skip costs. Night bonus by arrival time.

- NPCs only scout and attack players at war with them: declare war on an NPC's profile or by attacking it, its tribe joins, peace after warPeaceHours; war targeting, assaults, saving up, scouting pause
- One game lock for requests, the tick, NPC steps and sweeps: parallel orders no longer skip costs or overfill the farm, the tick no longer undoes a market charge
- HQ demolish shows the farm space each demolition frees, in the table and the confirmation
- Conquest: a later wave landing on a village its player just conquered stays as support
- Reports: the battle that took the village says "conquers" in the list and the subject
- Night bonus by the arrival time: battles, NPC attack plans and NPC dodging
- NPC war: no endless scouting of a village it is saving up for
- NPC war: one scouting party per target at a time, not one from every village in the same tick
- NPC war: failed scouting runs count per player, two pause scouting all of the player's villages
- NPCs rename the villages they conquer (&lt;name&gt;'s village 2, 3, ...); one-time rename of earlier conquests
- Profile: village names link to the village info
- Academy coin list: village names link to the village info
- NPC war pacing: at most warAttacksPerHour (5) attacks land on a player per hour from all NPCs, none at night (warNoNightAttacks)

### Groups, village overview and more fixes (19)

Map and village groups, village notes, overview polish, self-conquest and abandoned-village fixes, a startup fix, and the over-the-farm-limit fix.

- Achievements intro: conquering and tribes exist now
- Cancel buttons say "Cancel", not "Cancellation"
- Map: switching villages centres the map on the new village
- Map: the Center map fields follow a village switch too
- Map groups: colour players, tribes and villages on the map, stored per player
- Village overview: a building built for the first time shows its label, not an empty box
- Village overview: the building under construction shows its own remaining time, the headquarters the whole queue
- Village overview: the command pencil renames the command, like on the rally point
- Own commands no longer list incoming troops: separate Incoming troops table on the overview and the rally point
- Village overview: notes can be written and are saved per village and owner
- Village groups: overview Groups box (assign in place) and links to Overviews &gt; Groups from it and the header popup
- Overviews &gt; Groups: per-village OK saves instead of posting the page
- Village arrows: cycle all villages when the selected group lacks the current one
- Links open in a new tab on middle / ctrl / shift-click
- smoke test: create the DOM before importing React; cover newer screens
- Startup: start-building backfill no longer stops the server
- Self-conquest keeps the village's orders and armies
- Villages falling to the barbarians lose their armies abroad
- A village over its farm limit can still build the farm and warehouse: levels that take no farm space skip the farm check

---


## The World

TWLAN2 recreates the classic Tribal Wars experience as a persistent, singleplayer world.

Multiple worlds can run side by side, each with its own speed and population. AI-controlled villages and tribes develop, recruit armies, scout, farm, retaliate and conquer on their own. Wars can start, villages can change hands, and the world continues to evolve even when no human player is online.

The game follows the original Tribal Wars systems and mechanics as closely as practical / possible, while the implementation itself is built entirely from scratch.

## Features

* Multiple worlds running independently at different speeds
* Accounts with multiple villages
* Village development and resource management
* Building and technology progression
* Recruitment, scouting and combat
* Noblemen and conquest through loyalty
* AI-controlled NPC villages and tribes
* NPCs that build, recruit, scout, farm, retaliate and conquer
* Tribes with diplomacy, forums, wars and member support
* In-game mail and messaging
* Player-to-player market
* Achievements
* Paladin
* Day and night cycle
* Live browser updates using Server-Sent Events
* Administration panel for managing worlds, NPCs, accounts and barbarian villages

Game mechanics, values and systems are matched to the original Tribal Wars wherever possible.

## Technology

### Backend

* Java 21
* Spring Boot
* Gradle
* H2

Game state is stored in a local H2 file database. No external services or database servers are required.

### Frontend

* React
* Vite

The frontend uses the original game's CSS and mirrors its rendered markup to reproduce the classic Tribal Wars interface as closely as possible.

## Project Structure

```text
TWLAN2/
├── backend/       # Java / Spring Boot game server
├── frontend/      # React / Vite client
└── docker-compose.yml
```

## Running Locally

Start the backend:

```bash
cd backend
./gradlew bootRun
```

Start the frontend:

```bash
cd frontend
npm install
npm run dev
```

Then open:

**http://localhost:5173**

Game state is persisted in:

```text
backend/data/
```

This directory contains the actual world save. Back it up before modifying or removing it.

## Self-Hosting

TWLAN2 can be deployed using Docker Compose:

```bash
docker compose up --build
```

The Compose setup builds the Java backend and React frontend, with the frontend served through nginx.

The project is intended for personal and private use.


**The world does not wait.**
