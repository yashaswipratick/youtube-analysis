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
- OpenAI story audit of the full episode using Yash Xplore Five-Act Travel Story Format v2.3, with section-by-section findings and prioritized editorial fixes
- Context-aware OpenAI storytelling rewrite for non-empty voice-over sections, using the full episode to reduce repetition and improve transitions, with a preview and explicit Apply / Discard controls
- On-camera sections are never targeted for rewriting; they may be included as context so the voice-over does not duplicate recorded dialogue
- Rewrites use the server-side OpenAI API key file/configuration; keys are never placed in browser code
- Accepted rewrites save to localStorage immediately and enter the existing Cassandra sync flow
- Responsive desktop and mobile interface

## Data persistence
The regular project planner remains in this browser's localStorage. The **Write your own script** library also keeps an immediate local copy and synchronizes with Cassandra when you sign in with Google and the backend is available. Cassandra data is scoped to the authenticated Google subject; the browser cannot choose another owner's ID.

For the table layout and API contract, see [VlogScript Cassandra model](../../docs/vlogscript-cassandra-model.md).

For local sync, run Cassandra and the Spring Boot backend on port 8080, then serve the `front-end` directory on port 3000. The API host in `front-end/config.js` matches `localhost` or `127.0.0.1` based on the browser URL so the session cookie uses the matching hostname.

The browser copy is device/profile-specific. Export backups regularly, because clearing browser data can remove unsynchronized edits.

## AI story audit
Open **Write your own script**, open an episode, and choose **AI story audit**. The app sends the ordered episode sections (titles, types, timings, notes and script text) to the authenticated backend. OpenAI audits the story promise, primary story engine, hook-to-first-delivery pacing, five-act progression, section roles, VO/on-camera overlap, transitions and side quests, factuality flags, payoff and ending, and a small set of prioritized edits. The audit uses the v2.3 format and is advisory: it does not rewrite, apply or persist any episode changes.

Results are linked to the current draft snapshot. If you edit the episode after an audit, the panel marks the result as stale; re-run the audit before relying on it. Retention recommendations are hypotheses, not a claim that a particular scene caused viewers to leave. After publication, compare the suggested test signal with the actual YouTube Studio analytics available for that video.

## AI storytelling rewrite
Open **Write your own script**, open an episode, and choose **AI storytelling rewrite**. The app sends the non-empty voice-over sections to the authenticated backend along with the full episode context. The full context helps OpenAI identify repeated facts, connect transitions and distinguish what the voice-over should add beyond recorded on-camera material. Context can contain on-camera script text, but only selected voice-over sections are returned for rewriting. On-camera scripts are never targeted or automatically altered.

The prompt asks for natural spoken Hinglish where appropriate, restrained cinematic writing, organic pauses, practical length and strict preservation of supplied facts. Embedded instructions in script text are treated as untrusted content. The operation returns a preview; it does not overwrite anything until you choose **Use rewritten scripts**. **Discard preview** preserves the original text. Applying the rewrite saves changes locally and schedules the normal Cassandra sync. The backend reuses `OPENAI_API_KEY_FILE`, `OPENAI_BASE_URL` and `OPENAI_MODEL` from `application.yml`; the key is never sent to the browser. Google sign-in and a reachable backend are required. Both operations set `store: false` for the OpenAI Responses API. This API request setting should not be interpreted as a guarantee about every retention or processing practice that may apply to the OpenAI account.

### Debugging a rewrite
Restart the Spring Boot application after changing its Java source so the running JVM loads the new endpoint/service code. The IntelliJ Run console then prints a correlated sequence:

- `VOICEOVER_REWRITE_ENDPOINT_RECEIVED`: authenticated request reached the controller.
- `VOICEOVER_STORY_REWRITE_START`: number of supplied voice-over sections, source character count, episode and configured model.
- `OPENAI_VOICEOVER_REQUEST_SENDING`: the service is about to make the actual OpenAI Responses API request.
- `OPENAI_VOICEOVER_RESPONSE_RECEIVED`: OpenAI returned a response, including elapsed time, response status and token counts when supplied.
- `VOICEOVER_STORY_SECTION_RESULT`: per section, logs input/output character counts, whether the text changed, word overlap and OpenAI's `changesMade` explanation.
- `VOICEOVER_STORY_REWRITE_COMPLETE` and `VOICEOVER_REWRITE_ENDPOINT_SUCCESS`: the response was parsed and returned to the browser.

In the browser, open Developer Tools → Console and filter for `[VlogScript AI]` or `[VlogScript Sync]`. The console states explicitly when sign-in blocked a request, when a response arrived, when a preview was applied, and when Cassandra accepted the local changes. Full scripts and API keys are deliberately not printed into backend logs; the preview displays the actual rewritten script and the `What AI changed` explanation. If you see `No AI call happened`, no OpenAI request was sent. Sign in with Google first, then run the rewrite again.

## GitHub Pages
The deployment workflow runs from the `dev` branch. The optional Cassandra sync requires the API to be reachable from the browser; the Studio still supports local-only editing when the API is not reachable. If Pages has not been enabled for this repository, open Settings → Pages, choose GitHub Actions, and rerun the workflow under Actions.
