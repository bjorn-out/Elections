# Elections — In‑Game Usage Guide

This guide shows players and staff how to run elections entirely in‑game: simple commands, permissions, menus, voting, exports, and health checks.

Quick tip: the base command is `/elections` (alias: `/democracyelections`).

## Installation (server owner)
1) Drop the plugin jar into `plugins/` and start the server once.
2) Edit `plugins/Elections/config.yml`:
   - `mysql.*` — set host, port, database, user, password, and SSL.
   - `pastegg.apiKey` (optional) — set if you want to delete pastes via command.
   - Sweep intervals under `elections.*` (auto‑close and deleted purge).
3) Restart. The plugin creates missing config keys with defaults.
4) Grant permissions using your permissions plugin (LuckPerms, etc.).

## Permissions
- `elections.command` (default: op)
  - Allows access to the `/elections` command entrypoint. Without this, a player cannot run any Elections command.
- `elections.manager` (default: op)
  - Manager abilities (open Manager UI, create/edit/open/close elections). Also grants: `elections.command`, `elections.export`, `elections.health`, `elections.permissions.reload`.
- `elections.export` (default: op)
  - Allows all export operations under `/elections export` for full election exports (remote/local/both). Admin modes still require manager/admin as noted below.
- `elections.export.admin` (default: op)
  - Allows export with voter names: `/elections export admin <local|online> <id>`.
- `elections.export.ballots` (default: op)
  - Allows ballots‑only export: `/elections export ballots <local|online> <id>`.
- `elections.export.ballots.admin` (default: op)
  - Allows ballots‑only export with voter names: `/elections export ballots admin <local|online> <id>`.
- `elections.health` (default: op)
  - Allows `/elections health`.
- `elections.permissions.reload` (default: op)
  - Allows `/elections reloadperms`.
- `elections.delete` (default: op)
  - Extra gate for delete operations (used by `/elections export delete` and election deletion). Admins can also delete.
- `elections.admin` (default: op)
  - Admin umbrella. Inherits all Elections permissions (including `elections.command`).
- `elections.user` (default: true)
  - Player eligibility for voting and non-command features only. This does NOT grant access to any `/elections` commands.

Note: See `plugin.yml` for exact inheritance used by your build.

## Commands (in‑game)
All commands require `elections.command` plus the specific node:
- `/elections` or `/elections manager`
  - Opens the Elections Manager (GUI). Must be executed by a player. Requires: `elections.manager`.
- `/elections export <id>`
  - Publish full election data online (paste.gg). No voter names. Requires: `elections.export`.
- `/elections export local <id>`
  - Save the full election export to the server (no upload). Requires: `elections.export`.
- `/elections export both <id>`
  - Publish online and also keep a local copy. Requires: `elections.export`.
- `/elections export admin <id>` / `admin local <id>` / `admin both <id>`
  - Admin full‑election export (includes voter names). Requires: `elections.export.admin` + `elections.manager` (or `elections.admin`).
- `/elections export ballots <local|online> <id>`
  - Export only ballots as JSON (pretty printed). Includes the candidates map and each ballot as an array of candidate IDs. Requires: `elections.export.ballots` (or `elections.export`).
- `/elections export ballots admin <local|online> <id>`
  - Export only ballots as JSON (pretty printed) with voter names. Ballots are objects with `id`, `voter`, and `selections`. Requires: `elections.export.ballots.admin` (or `elections.admin`).
- `/elections export delete <pasteId> confirm`
  - Delete a paste on paste.gg (needs a valid API key). Requires: `elections.export` + (`elections.delete` or `elections.manager`/`elections.admin`).
- `/elections export dispatch`
  - Process the local queue now (uploads pending files). Requires: `elections.manager` (or `elections.admin`).
- `/elections reloadperms`
  - Reloads the YAML permission nodes filter used by the plugin. Requires: `elections.permissions.reload`.
- `/elections health`
  - Shows basic health stats (counts, DB latency, config warnings). Requires: `elections.health`.

Tab completion:
- You’ll only see subcommands you’re allowed to run (based on your permissions).
- For `export`, suggestions include IDs and options shown contextually: `admin`, `local`, `both`, `delete`, `dispatch`, `ballots`, and `ballots admin`.

## Staff workflow (Manager UI)
1) Open the Manager: `/elections`.
2) Create or edit an election:
   - Title
   - Voting System:
     - `BLOCK` — players must select exactly the configured minimum number of candidates.
     - `PREFERENTIAL` — players order candidates by preference (no duplicates) and must meet the minimum.
   - Minimum Votes — interpreted per system (see above).
   - Duration — days and time (H:M:S). When set, elections auto‑close after the configured duration.
   - Voter Requirements — one or both:
     - Specific permission nodes the player must have.
     - Minimum active playtime (configurable units, e.g., hours).
   - Ballot Mode — UI presentation for ballots.
3) Candidates — add candidates in the main menu. Use "Candidate List" to view and remove existing candidates (with confirmation).
4) Polls (booths) — define locations where players can cast ballots:
   - Choose “Define Poll” and right‑click a block/head in the world to register the booth.
   - “Undefine Poll” to remove specific booths.
   - “Remove All Polls” to clear all booths for the election at once.
   - Booth locations are globally unique: two different elections cannot use the same block. If you try, you’ll get an error telling you which election already owns that location (ID and title).
5) Open/Close — when ready, open the election. It will auto‑close if a duration is set, or you can close manually. Note: Opening the manager for an OPEN election requires confirmation to prevent accidental edits during voting.

## Player workflow (voting)
1) Go to a defined poll (booth) and right‑click the block/head.
2) If you have `elections.user` and meet any configured requirements (extra nodes or playtime), the ballot UI opens.
3) Fill your ballot:
   - `BLOCK`: select exactly the required number of candidates.
   - `PREFERENTIAL`: order candidates by preference (no duplicates), meeting the minimum.
4) Submit the ballot. You can’t vote more than once per election; duplicates are blocked.

## Exports
Simple choices depending on your need:
- Full election (standard):
  - Online only: `/elections export <id>` — posts JSON to paste.gg (no voter names) and returns the view URL.
  - Local file only: `/elections export local <id>` — writes a JSON file to the server’s local queue (no upload).
  - Both: `/elections export both <id>` — publishes online and also saves a local copy.
  - Admin (includes voter names):
    - Online only: `/elections export admin <id>`
    - Local only: `/elections export admin local <id>`
    - Both: `/elections export admin both <id>`
- Ballots‑only (new):
  - Online only: `/elections export ballots online <id>`
  - Local only: `/elections export ballots local <id>`
  - Admin with voter names: `/elections export ballots admin <local|online> <id>`

Where local files live (server filesystem):
- Full election queue: `plugins/Elections/exports/queue` (pending), `sent` (archived), `failed` (errors).
- Ballots‑only: `plugins/Elections/exports/ballots/`
  - Non‑admin: `ballots-<electionId>-<epoch>.json`
  - Admin: `ballots-admin-<electionId>-<epoch>.json`

Ballots‑only JSON shape (pretty printed):
- Non‑admin export
  ```json
  {
    "candidates": {
      "1": "Bob",
      "2": "Jane"
    },
    "ballots": [
      [1, 2, 3],
      [2, 3]
    ]
  }
  ```
- Admin export
  ```json
  {
    "candidates": {
      "1": "Bob",
      "2": "Jane"
    },
    "ballots": [
      { "id": 101, "voter": "Alice", "selections": [1, 2, 3] },
      { "id": 102, "voter": "Chris", "selections": [2, 3] }
    ]
  }
  ```

How “dispatch” works:
- It tries to publish every JSON in the queue.
- If an election was already exported, the file is removed and counted as “skipped”.
- Successes are moved to `sent/` with the paste id; failures stay in the queue or move to `failed/` when invalid.
- When online publishing fails during a normal export, the plugin automatically saves a local copy and tells you: “Remote publish failed, saved to local queue …”.

Notes:
- Set `pastegg.apiKey` in config to enable authorized deletions; otherwise delete will likely fail.
- Successful remote exports are recorded in the election’s status log.

## Health
- `/elections health` shows:
  - Count of elections (open/closed/deleted), voters, ballots.
  - Auto‑close sweep interval.
  - Current DB latency (quick `SELECT 1`).
  - Warnings for common misconfigurations (e.g., missing paste.gg API key).

## Configuration quick reference
In `config.yml`:
- `mysql.host`, `mysql.port`, `mysql.database`, `mysql.user`, `mysql.password`, `mysql.useSSL` — database connection.
- `elections.autoCloseSweepSecods` — seconds between auto‑close sweeps (typo kept for compatibility).
- `elections.deletedPurgeSweepSeconds` — how often to purge elections marked DELETED beyond retention.
- `elections.deletedRetentionDays` — retention for DELETED elections before purge.
- `pastegg.apiBase`, `pastegg.viewBase`, `pastegg.apiKey` — paste.gg endpoints and key.

> MariaDB compatibility: You can point the above `mysql.*` settings at a MariaDB server. The plugin ships with MySQL Connector/J (8.x), which is wire‑protocol compatible with MariaDB for the SQL features used here (DDL, indexes, FKs, `utf8mb4`). No extra driver is required in typical setups—just use your MariaDB host/port/database/user/password. If your environment mandates MariaDB Connector/J specifically, the SQL remains compatible; reach out if you need a build that bundles it.

## Troubleshooting
- “This election is not open.” — staff must open it in the Manager.
- “You don’t have permission to vote.” — ensure `elections.user` and any extra nodes in the election requirements.
- “You are not eligible to vote.” — you’re missing a required node or playtime minutes.
- “You have already submitted a ballot for this election.” — duplicates are blocked per voter.
- “A poll already exists here” — the block is already used by another election; the message shows which election owns it.
- “Remote publish failed, saved to local queue …” — online upload failed; use `/elections export dispatch` later to send queued files.
- “Export failed” — check paste.gg connectivity and that `pastegg.apiKey` is set for delete operations.

## UI customization (AutoYML)
The entire in‑game UI is configurable. Each menu defines a small Config class, and the plugin uses an AutoYML utility to generate a YAML file for that class the first time the menu is opened.

- Where configs live
  - Under your plugin data folder: `plugins/Elections/menus/`
  - File names match the menu and, for some menus, include a context suffix (e.g., an election id).
- How to edit
  - Open the menu once to have the YAML created with default values and a header comment.
  - Edit only the values (strings, colors via MiniMessage, etc.). Don’t rename keys unless you know what you’re doing.
  - Placeholders are supported (e.g., `%player%` plus menu‑specific ones). Each YAML includes a header listing the placeholders available for that menu.
- Reloading changes
  - Just close and re‑open the menu. The config is loaded when the dialog is built; no server restart is required.
- Tips
  - Keep files UTF‑8. Back up before large edits.
  - If multiple staff edit the same YAML, avoid concurrent edits while the server is writing.
