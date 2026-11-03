# Issue backlog

The original initialization is complete. Issues #2–#8 now cover the delivered app; #9 is the release verification gate. Scope follows the requested Firebase backend: initial account/consent/key setup is online, subsequent local workflows and encrypted file exchange work offline. Follow [the roadmap](ROADMAP.md).

## Initialize the TrainPit repository

Tracking: [#1](https://github.com/ManfuG/TrainPit/issues/1).

- [x] Establish the four-module Android/Kotlin scaffold, project guidance, contribution workflow, and initial backlog.

## Define trainer and client offline workflows

Tracking: [#2](https://github.com/ManfuG/TrainPit/issues/2).

- [x] Implement fixed trainer/client roles, multiple consenting clients, and local workflows after online account setup.
- [x] Preserve trainer ownership of plans and client ownership of sessions/body metrics.
- [x] Enforce active client-approved connections and separate body-weight opt-in.
- [x] Exercise role isolation, denied access and offline behavior with synthetic users.

## Establish secure offline local storage

Tracking: [#3](https://github.com/ManfuG/TrainPit/issues/3).

- [x] Persist per-account Room records across offline restarts, with an exported schema and no destructive fallback.
- [x] Encrypt sensitive row values with Android Keystore AES-GCM; keep private signing keys out of files and Firebase.
- [x] Disable OS backup/transfer and document corruption, key-loss and migration-failure limits.
- [x] Verify shared repository concurrency, persistence, key destruction and stale-reference rejection.

## Create and assign versioned weekly training plans

Tracking: [#4](https://github.com/ManfuG/TrainPit/issues/4).

- [x] Create weekly plans with training days, exercises, sets, repetitions and optional target loads.
- [x] Assign only to the intended active client; enforce trainer ownership.
- [x] Publish changes as immutable signed revisions.
- [x] Retain exact plan references from sessions and make downloaded plans available offline.
- [x] Exercise multi-client assignment, two-day schedules and updated-plan delivery.

## Record client workout logs and body metrics

Tracking: [#5](https://github.com/ManfuG/TrainPit/issues/5).

- [x] Create, view and correct owned sessions, actual sets/repetitions/load, and optional historical plan references.
- [x] Create, view and correct body-weight measurements with calendar dates and integer-gram storage.
- [x] Preserve offline edits and distinguish causal corrections from new records.
- [x] Keep trainer monitoring read-only and consent-scoped.
- [x] Verify original revisions survive corrections and prescribed targets are not counted as actual results.

## Add role-scoped dashboards charts and statistics

Tracking: [#6](https://github.com/ManfuG/TrainPit/issues/6).

- [x] Deliver weekly schedules and progress views. Version 1.0.1 simplifies Overview to today's workout and the body-weight trend; weekly schedules stay in Plans and record review stays in History & stats.
- [x] Restrict trainer summaries to selected authorized client records.
- [x] Define periods, units, missing-data and conflict behavior in DATA_MODEL.md.
- [x] Verify v1.0.0 synthetic totals: two sessions, three completed sets, 25 repetitions and 1300 kg × reps; verify empty-client isolation.

## Implement Firebase synchronization and encrypted history import/export

Tracking: [#7](https://github.com/ManfuG/TrainPit/issues/7).

- [x] Specify the versioned PBKDF2/AES-GCM file format and explicit password sharing.
- [x] Export complete authorized histories or selected sessions, with available dependencies and causal ancestry.
- [x] Synchronize immutable owned revisions and authorized remote histories with upload acknowledgements and transactional download cursors.
- [x] Make duplicate/import replay idempotent; reject corrupt, unsupported or unauthorized archives atomically.
- [x] Preserve concurrent heads and expose owner-only conflict resolution instead of last-write-wins.
- [x] Verify archive failures, branches, round trips, duplicate import and Android file-picker relocking. Exports never acknowledge cloud uploads.

## Implement privacy access and recovery controls

Tracking: [#8](https://github.com/ManfuG/TrainPit/issues/8).

- [x] Implement acceptance, weight opt-in and revocation, with default-deny deployed rules.
- [x] Purge revoked foreign local records on synchronization; explain that previously received external copies cannot be recalled.
- [x] Provide authorized encrypted export, signed logical deletion and genuine account-local database/key erasure.
- [x] Recover synchronized owned history after local erasure without exporting private keys; document unsynchronized-data/password/key-loss limits.
- [x] Verify deployed-server denial after revocation and owned-history recovery with synthetic data only.

## Validate offline workflows and release readiness

Tracking: [#9](https://github.com/ManfuG/TrainPit/issues/9).

- [x] Record native signed-app workflow coverage on the minimum supported and a current Android API level (26 and 36).
- [x] Run 17 JVM behavior tests, five Android persistence/cloud/account tests and 12 adversarial Firestore-rule tests.
- [x] Exercise trainer publication, client logging, corrections, explicit consent, revocation and recovery against the real Firebase project.
- [x] Exercise offline restarts, archive round trips, duplicate import and long file-picker credential relocking.
- [x] Remove production verification fixtures and record reproducible results/coverage limits.

Release gate (tracked in #9): publish a signed, non-debuggable APK and checksum from the verified commit; verify GitHub CI and the downloaded asset.
