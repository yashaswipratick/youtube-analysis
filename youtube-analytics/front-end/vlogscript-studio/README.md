# VlogScript Studio

A responsive script planner for travel vlogs, with a scene-by-scene project workspace and a trip-based episode/script library.

## Features
- Multiple projects and editable project metadata
- Scene headings, subheadings, purpose, location, duration, and director notes
- Separate voice-over and on-camera script areas
- Customisable shot checklist with camera angles, 4K/30, 4K/60, 4K/100 FPS, orientation, and completion status
- Progress indicators, scene duplication/reordering, and shot completion
- Trip-based script library: every visit has a unique trip ID, and every trip can contain multiple ordered episodes
- Duplicate destination names are supported, so repeat visits to Coorg remain separate
- Browser-local persistence, JSON export/import, and print-friendly plans
- Optional Google sign-in sync with the backend's Cassandra store; local editing remains available offline
- Responsive desktop and mobile interface

## Data persistence
The regular project planner remains in this browser's localStorage. The **Write your own script** library also keeps an immediate local copy and synchronizes with Cassandra when you sign in with Google and the backend is available. Cassandra data is scoped to the authenticated Google subject; the browser cannot choose another owner's ID.

For the table layout and API contract, see [VlogScript Cassandra model](../../docs/vlogscript-cassandra-model.md).

For local sync, run Cassandra and the Spring Boot backend on port 8080, then serve the `front-end` directory on port 3000. The API host in `front-end/config.js` matches `localhost` or `127.0.0.1` based on the browser URL so the session cookie uses the matching hostname.

The browser copy is device/profile-specific. Export backups regularly, because clearing browser data can remove unsynchronized edits.

## GitHub Pages
The deployment workflow runs from the `dev` branch. The optional Cassandra sync requires the API to be reachable from the browser; the Studio still supports local-only editing when the API is not reachable. If Pages has not been enabled for this repository, open Settings → Pages, choose GitHub Actions, and rerun the workflow under Actions.
