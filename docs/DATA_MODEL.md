# Data model

Implemented types live in `core:model/.../Models.kt`; persistence implements `LocalRepository`. See [roles](ROLES_AND_PRIVACY.md), [synchronization](SYNCHRONIZATION.md) and [README](../README.md).

## Accounts and relationships

| Type | Contents and ownership |
| --- | --- |
| `Profile` | Firebase UID, display name and fixed `TRAINER`/`CLIENT` role. Names are labels, not proof of professional qualifications. |
| `Relationship` | Deterministic trainer/client UID pair; both names; `PENDING`, `ACTIVE` or `ENDED`; separate `shareBodyWeight` consent. Trainer requests, client accepts, either ends. |
| `DeviceKey` | Owner UID, SHA-256 public-key fingerprint/device ID, Base64 X.509 P-256 public key and revocation flag. Firebase owner-authenticated registration establishes trust; archive keys do not. |

A trainer manages multiple independent client relationships. Admin never means access to another trainer's clients. Clients own sessions/body weight; trainers own plans. The same client can consent independently to different relationships.

## Training records

| Type | Stored facts |
| --- | --- |
| `WorkoutPlan` | Intended client UID, title, Monday `weekStart`, and distinct training days. The client/week fields constitute assignment; there is no mutable “latest plan” pointer. |
| `WorkoutDay` | ISO weekday 1–7 and ordered exercise prescriptions. Omitted days are rest/unassigned days. |
| `ExercisePrescription` | Stable exercise ID, name, target sets/repetitions and optional external load. Exercise IDs are unique across the plan. |
| `TrainingSession` | Calendar date, title and actual performed sets; optional exact plan-revision ID and weekday. Names/results are self-contained even if the original plan later becomes unavailable. |
| `SetResult` | Stable set ID, exercise-name snapshot, actual repetitions/external load and optional prescribed-exercise ID. Planned values never become actual results automatically. |
| `BodyWeight` | Measurement date and body mass. Sharing is separately controlled per trainer. |

Weights are integer **grams**; UI/interchange use kilograms with at most three decimal places. External training load may be zero for unloaded/body-weight-only work; body mass must be positive. Repetitions are integers. Supported limits: 7 training days, 40 exercises/day, 1–30 target sets, 1–1000 target repetitions, up to 400 actual sets/session, 0–1000 actual repetitions and at most 1000 kg. Empty/unknown target load is null, never an inferred actual value.

Dates are real `YYYY-MM-DD` calendar dates, years 0001–9999. A week begins on Monday. Dates are intentionally date-only, not fabricated UTC event instants; authoring time is recorded separately. Sorting fixed-width dates agrees with calendar order.

## Immutable revisions

`Entry` wraps exactly one plan, session or body-weight record with:

- `schemaVersion = 1`, unique revision `id`, stable `rootId`, owner/subject UIDs and record kind;
- author device ID, descriptive authoring timestamp, parent revision IDs and deletion flag;
- the domain payload and ECDSA/SHA-256 signature.

The root revision's ID equals `rootId` and has no parents. Subsequent revisions name complete same-root, same-owner, same-subject, same-kind ancestry. Changing a title, result or assignment creates a new signed revision; existing session plan references do not change. Device time and arrival order never choose a winner.

Head revisions are those not superseded by descendants. Multiple heads remain unresolved and are excluded from ordinary statistics until the owner chooses a branch and publishes a resolution. A tombstone is a signed deletion revision, not physical erasure. Concurrent delete/edit branches stay explicit. Resolution/deletion fan-in is bounded to 100 parents/revision; larger conflicts use an atomic chain of merge revisions.

A signed serialized entry is limited to 262144 characters. IDs cannot contain path separators/control characters; cyclic, incomplete, cross-owner or conflicting-ID histories are rejected. Version changes must preserve the version-1 signing representation or introduce an explicit new schema; never reinterpret old signatures using a changed serializer.

## Local persistence

Each authenticated UID has a separate Room database, schema version 1. SQLite indexes retain opaque record/root/owner/subject IDs, kind and pending status. Record contents, relationship metadata, device-key caches, profile metadata and cloud cursors are encrypted with a per-account Android Keystore AES-256-GCM key. Index metadata and record counts are not concealed by row encryption.

Domain revisions and their pending-upload flags commit atomically. Remote records and their receive cursor commit atomically. A process-wide, reference-counted account repository shares state between UI and background workers; immutable records are cached after decoding. Closing one user of the repository cannot close another's store. Explicit local erasure invalidates every reference before removing database/WAL/SHM and account keys.

Room has no destructive migration fallback. Future schema changes require explicit migrations and behavioral tests. Missing keys, corrupt authenticated payloads and unsupported schemas fail closed without resetting existing records. OS/cloud backup and device-transfer extraction are excluded; unsynced records need a successful cloud upload or usable encrypted archive before local erasure.

## Derived information

Overview selects the current calendar day's workout from the current Monday-based weekly plans, and the latest 20 body-weight points from authorized, non-deleted, non-conflicted heads. Omitted training days show a rest-day empty state. Full weekly schedules stay in Plans; History & stats lists retained records and explicit conflicts. Derived views are not synchronized or stored as authoritative health facts.
