# Issue backlog

Repository initialization is the only completed item. All other entries describe future work; none implies an implemented feature. Linked issues track delivery. Follow [the roadmap](ROADMAP.md) in order.

## Initialize the TrainPit repository

Tracking: [#1](https://github.com/ManfuG/TrainPit/issues/1).

- [x] Establish the four-module Android/Kotlin scaffold, project guidance, contribution workflow, and planned backlog without feature implementation.

## Define trainer and client offline workflows

Tracking: [#2](https://github.com/ManfuG/TrainPit/issues/2).

Define the role model and the smallest usable local-only journeys before implementing features.

- [ ] Specify trainer management of multiple clients and client journeys that work without a backend or network connection.
- [ ] Define trainer ownership of plans and client ownership of workout logs and body metrics.
- [ ] Define explicit consent-scoped trainer access to client records, including denied-access scenarios.
- [ ] Record agreed workflow acceptance scenarios using synthetic users and data.

## Establish secure offline local storage

Tracking: [#3](https://github.com/ManfuG/TrainPit/issues/3).

Persist sensitive domain records locally using the agreed Android stack, without coupling data and sync modules.

- [ ] Store and retrieve records across offline application restarts with defined schema evolution behavior.
- [ ] Select supported storage protection and key-management mechanisms; document the threat model and limits without custom cryptographic primitives.
- [ ] Define backup eligibility, key loss, corruption, and migration failure behavior without silently discarding records.
- [ ] Verify persistence and protection behavior with synthetic records, including failure paths.

## Create and assign versioned weekly training plans

Tracking: [#4](https://github.com/ManfuG/TrainPit/issues/4).

Allow trainers to author weekly training plans and clients to use assigned plans offline.

- [ ] Define and implement weekly plan content sufficient for the agreed trainer/client workflow.
- [ ] Support trainer authoring, publication, and assignment to the intended client.
- [ ] Preserve immutable published revisions and publish edits as new revisions.
- [ ] Keep assigned plans usable offline and retain revision identity when newer revisions arrive.
- [ ] Verify ownership and assignment boundaries and revision preservation with synthetic scenarios.

## Record client workout logs and body metrics

Tracking: [#5](https://github.com/ManfuG/TrainPit/issues/5).

Allow clients to maintain their own training history and body measurements offline.

- [ ] Create, view, and correct client-owned training sessions and set results, including weights and repetitions, retaining the relevant plan revision reference.
- [ ] Create, view, and correct body-weight measurements with defined units and recorded measurement times.
- [ ] Preserve offline records across restarts and distinguish corrections from unrelated new records.
- [ ] Restrict trainer visibility to explicitly consented client data without transferring ownership.
- [ ] Verify corrections, revision references, and access boundaries using synthetic data.

## Add role-scoped dashboards charts and statistics

Tracking: [#6](https://github.com/ManfuG/TrainPit/issues/6).

Present progress from available local data without expanding the product into new tracking domains.

- [ ] Implement workout schedules, progress charts, and statistics for client workout logs and body metrics.
- [ ] Provide trainer summaries limited to consented clients and authorized records.
- [ ] Define calculation rules, units, periods, and empty or incomplete history behavior.
- [ ] Verify calculated results against synthetic datasets and confirm offline use and role isolation.

## Implement encrypted synchronization and history import/export

Tracking: [#7](https://github.com/ManfuG/TrainPit/issues/7).

Use explicit portable file import/export as the initial transport; no server or nearby transport is required.

- [ ] Specify a versioned encrypted bundle format using established cryptographic libraries, with an explicit key-sharing approach.
- [ ] Export and import complete client histories or selected training sessions with their dependencies, preserving ownership, identifiers, immutable revisions, and history.
- [ ] Exchange authorized deltas and acknowledgements between paired devices using the documented replay-safe, scoped protocol.
- [ ] Make repeated and interrupted imports safe; reject unsupported, corrupt, or unauthorized bundles with explicit outcomes.
- [ ] Surface unresolved conflicts without wall-clock last-write-wins or silently replacing divergent records.
- [ ] Verify round trips, duplicate imports, divergent histories, and failure cases with synthetic bundles.

## Implement privacy access and recovery controls

Tracking: [#8](https://github.com/ManfuG/TrainPit/issues/8).

Complete user-facing controls around sensitive local data and exchanged copies.

- [ ] Provide explicit consent grant and revocation flows and enforce role-scoped access.
- [ ] Explain and test that revocation cannot erase copies already received by an offline recipient.
- [ ] Define and implement authorized personal-data export and deletion, including the limits for distributed copies.
- [ ] Provide recovery behavior for supported backup and key-loss scenarios, clearly identifying unrecoverable cases.
- [ ] Verify consent changes, denied access, deletion, and recovery without using real client data.

## Validate offline workflows and release readiness

Tracking: [#9](https://github.com/ManfuG/TrainPit/issues/9).

Validate the integrated planned behavior rather than treating successful compilation as acceptance.

- [ ] Exercise trainer publication and assignment, client logging and measurements, and dashboards end to end on supported Android versions.
- [ ] Verify offline restarts, storage failures, consent boundaries, and immutable revision references.
- [ ] Exercise encrypted file round trips, duplicates, interrupted imports, explicit conflicts, and recovery scenarios.
- [ ] Check that reports, fixtures, diagnostics, and screenshots contain no private client data or secrets.
- [ ] Record reproducible verification results and known limitations against the agreed acceptance scenarios.
