# 0012 The Fauxnance key and a database password stay in the repository

| Field | Value |
|---|---|
| Status | accepted, as a known finding |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

`executor/src/main/resources/application.yml` is committed and contains our Fauxnance API key and the local Postgres password. Both were committed before we caught it, so they are already in the history. The instructor's review flagged this.

## Options considered

| Option | For | Against |
|---|---|---|
| Delete the file and keep the values in a local `.env` | Stops it getting worse | Does not undo the exposure, since the values are in the history, and the key belongs to the whole cohort so we cannot rotate it ourselves |
| Rewrite the git history | Removes the values from history | Disruptive for four people on a shared branch days before the showcase |
| Leave it and record the exposure | No disruption | The values stay readable |

## Decision

Leave the file where it is for now and record it. Real values belong in `.env`, which is ignored, and the running services read their settings from there; `services/executor-service` only has `application.yml.example` checked in.

What is exposed: a Fauxnance key that allows 2,000 requests a day for the cohort, and the password of the local development database. That database listens on all interfaces of this machine, so anyone on the same network who knew the password could connect. Someone with the key could use up our quota or read quote data.

## Consequences

- If the key is ever rotated or misused, replace it in `.env` and delete the old `executor/` copy.
- The security review lists this as an accepted finding.
- Nothing new with a secret in it may be committed.
