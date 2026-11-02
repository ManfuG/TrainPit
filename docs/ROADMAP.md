# Roadmap

Only repository initialization is complete. Every phase below is planned, not implemented. Proceed in order; each phase requires its acceptance criteria before the next expands the product. See [the issue backlog](ISSUES.md) for the matching work items.

## 1. Roles and offline workflows

- Define trainer management of multiple clients, client journeys, ownership, consent boundaries, and local-only operation without a backend.
- Accept when synthetic scenarios distinguish a trainer's own plans, a client's own records, and explicitly consented trainer access.

## 2. Secure local storage

- Establish local persistence and supported platform security mechanisms before storing sensitive records.
- Accept when data survives offline restarts, storage protection and key lifecycle are documented, and loss or corruption has explicit outcomes.

## 3. Weekly training plans

- Support trainer-authored weekly plans, publication, assignment, and immutable published revisions.
- Accept when a client can view an assigned week offline and existing records retain their exact plan revision after later publication.

## 4. Logging and body metrics

- Support client-owned training sessions, set weights and repetitions, and body-weight measurements linked to the appropriate plan revision where relevant.
- Accept when clients can record and correct their own data offline without granting trainers ownership or implicit access.

## 5. Dashboards, charts, and statistics

- Present workout schedules, progress charts, statistics, and consent-scoped trainer summaries derived from available local records.
- Accept when calculations have defined units, periods, and missing-data behavior, and synthetic datasets verify results and access boundaries.

## 6. Portable synchronization and history import/export

- Start with explicit encrypted portable bundle exchange of complete client histories or selected sessions and dependencies, including deltas and conflict handling; no backend is required.
- Accept when repeated or interrupted imports are safe, revisions and provenance survive round trips, and unresolved conflicts are visible rather than resolved by wall-clock last-write-wins.
- Nearby transport is optional later work, not a prerequisite or a separate implementation commitment.

## 7. Privacy, access, and recovery

- Complete consent, revocation, export/deletion, and recovery flows around local records and exchanged copies.
- Accept when role-scoped access is enforced, recovery limits are explicit, and users understand that revocation cannot remotely erase an already received offline copy.
- Use established cryptographic constructions and supported libraries; do not invent cryptographic primitives.

## 8. End-to-end validation

- Validate the assembled workflows with synthetic data on supported Android versions, including offline and failure paths.
- Accept when agreed role, revision, privacy, storage, import/export, conflict, and recovery scenarios have reproducible results and documented limitations.
