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
| `WorkoutPlan` | Intended client UID, title, Monday `weekStart`, and distinct training days. One logical slot per trainer/client/week; corrections retain the root and original facts. |
| `WorkoutDay` | ISO weekday 1–7 and ordered exercise prescriptions. Omitted days are rest/unassigned days. |
| `ExercisePrescription` | Stable exercise ID, name, target sets/repetitions and optional external load. Exercise IDs are unique across the plan. |
| `TrainingSession` | Calendar date, title and actual performed sets; optional exact plan-revision ID and weekday. Names/results are self-contained even if the original plan later becomes unavailable. |
| `SetResult` | Stable set ID, exercise-name snapshot, actual repetitions/external load and optional prescribed-exercise ID. Planned values never become actual results automatically. |
| `BodyWeight` | One logical measurement per client/calendar date. Repeated entry corrects that day's record; sharing remains separately controlled per trainer. |

Weights are integer **grams**; UI/interchange use kilograms with at most three decimal places. External training load may be zero for unloaded/body-weight-only work; body mass must be positive. Repetitions are integers. Supported limits: 7 training days, 40 exercises/day, 1–30 target sets, 1–1000 target repetitions, up to 400 actual sets/session, 0–1000 actual repetitions and at most 1000 kg. Empty/unknown target load is null, never an inferred actual value.

Dates are real `YYYY-MM-DD` calendar dates, years 0001–9999. A week begins on Monday. Dates are intentionally date-only, not fabricated UTC event instants; authoring time is recorded separately. Sorting fixed-width dates agrees with calendar order.

Trainer rollover copies the most recent unambiguous prior plan directly into the current week for active clients, retaining exercise IDs and past weeks. Deleted/conflicted current or latest prior slots do not auto-repeat. Missed weeks do not generate an unbounded chain. While the trainer is offline, clients can display the inherited schedule with current-week dates and retain its exact signed source reference; they never author trainer revisions.

## Immutable revisions

`Entry` wraps exactly one plan, session or body-weight record with:

- `schemaVersion` 1 or 2, unique revision `id`, stable `rootId`, owner/subject UIDs and record kind;
- author device ID, descriptive authoring timestamp, same-root `parents`, deletion flag, and optional schema-2 `slotParents`;
- the domain payload and ECDSA/SHA-256 signature.

The root revision's ID equals `rootId` and has no parents. Subsequent revisions name complete same-root, same-owner, same-subject, same-kind ancestry. Changing a title, result or assignment creates a new signed revision; existing session plan references do not change. Device time and arrival order never choose a winner.

Head revisions are those not superseded by descendants. Multiple heads remain unresolved and are excluded from ordinary statistics until the owner chooses a branch and publishes a resolution. A tombstone is a signed deletion revision, not physical erasure. Concurrent delete/edit branches stay explicit. Resolution/deletion fan-in is bounded to 100 parents/revision; larger conflicts use an atomic chain of merge revisions.

`recordHeads` groups independent roots occupying the same owner/client/week plan slot or client/date weight slot as explicit conflicts. An owner-chosen schema-2 revision names exact cross-root competing heads in `slotParents`; original roots and facts remain intact. New references require the same owner, client, kind and calendar slot. Descendants retain these signed references across corrections or moves. A future competing descendant reopens the conflict. Genuine deletion plus an independently stale first save also remains a conflict, not silent resurrection.

Rootless saves update an existing unambiguous slot. Explicit edits cannot move into another occupied slot or edit a stale/deleted head. An intentional new save can restore a uniquely deleted slot as a descendant of its tombstone; automatic rollover cannot. Import/cloud duplicates are preserved for owner review, not collapsed by arrival time.

A signed serialized entry is limited to 262144 characters. IDs cannot contain path separators/control characters; cyclic, incomplete, cross-owner or conflicting-ID histories are rejected. Empty `slotParents` is omitted even with default serialization enabled, preserving the exact schema-1 signing representation. Nonempty references require schema 2. Older app versions must update before reading these resolutions. Room remains schema 1; there is no destructive database migration.

## Local persistence

Each authenticated UID has a separate Room database, schema version 1. SQLite indexes retain opaque record/root/owner/subject IDs, kind and pending status. Record contents, relationship metadata, device-key caches, profile metadata and cloud cursors are encrypted with a per-account Android Keystore AES-256-GCM key. Index metadata and record counts are not concealed by row encryption.

Domain revisions and their pending-upload flags commit atomically. Remote records and their receive cursor commit atomically. A process-wide, reference-counted account repository shares state between UI and background workers; immutable records are cached after decoding. Closing one user of the repository cannot close another's store. Explicit local erasure invalidates every reference before removing database/WAL/SHM and account keys.

Room has no destructive migration fallback. Future schema changes require explicit migrations and behavioral tests. Missing keys, corrupt authenticated payloads and unsupported schemas fail closed without resetting existing records. OS/cloud backup and device-transfer extraction are excluded; unsynced records need a successful cloud upload or usable encrypted archive before local erasure.

## Derived information

Overview selects today's workout from the current Monday-based schedule and the latest 20 unambiguous body-weight records. Full current-week schedules stay in Plans; historical records stay in History & stats. Weekdays accompany displayed dates. Chart points expose their date/value by tap and accessibility actions.

Session details derive each exercise's daily maximum external load and total repetitions from authorized, non-deleted, non-conflicted session heads, never old corrections. Normalized exercise names bridge weekly prescription IDs; ambiguous same-name prescriptions stay separate. Zero values are real results, missing dates are not invented, and chart spacing follows actual calendar dates. Rendered long series preserve interval extremes while selection retains all points. These derived views are never synchronized as health facts.
