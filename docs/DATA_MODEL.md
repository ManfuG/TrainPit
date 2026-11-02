# Proposed data model

This is a design contract, not an implemented schema. No database, migration, synchronization, or security guarantees exist yet. See [roles and privacy](ROLES_AND_PRIVACY.md), [synchronization](SYNCHRONIZATION.md), and the [project README](../README.md).

## Storage and identifiers

- Use one local Room database per local identity. Switching identity closes the previous database; never combine identities into one unfiltered store. This is application-level isolation, not a claim of encryption at rest.
- Within a trainer identity, partition client data by relationship and client identity. Every query, export, and import must carry that scope; a client selector is not an authorization check.
- Use random, globally unique, stable identifiers, independent of names, device clocks, or local database row numbers. Imported identifiers retain their meaning. The same identifier with different immutable contents is an error, not an overwrite.
- Local primary keys, file paths, cache state, and database schemas are not interchange formats. Charts and aggregates are derived locally from authorized source records and are never synchronized.
- Ownership belongs to an identity, not a device. An identity can authorize multiple devices. One owning identity per mutable aggregate does not imply only one device can edit it.

## Identity and access records

| Record | Proposed contents and invariants |
| --- | --- |
| Identity | Stable identity ID, root verification key, display label. The root key binds the identity; labels and claimed role are not proof of a real-world person or access rights. Root replacement/recovery needs an explicit trust workflow, not a label match. |
| Device | Device ID, owning identity ID, device signing/encryption public keys, root-authorized certificate, certificate version and retirement/revocation evidence. Private keys never enter ordinary exchanges. Device credentials cannot self-authorize a new identity or client relationship. |
| Relationship | Stable relationship ID, trainer identity ID, client identity ID, state and explicit acceptance evidence from both parties. Scope records always name this relationship; consent is not transitive. |
| Grant | Immutable grant/revision ID, relationship, issuer, subject identity/device, allowed record categories and actions, optional validity bounds, parent revision IDs and revocation evidence. A client controls disclosure of client-owned data; a trainer controls publication of trainer-owned plans. A received grant never silently broadens existing local consent. |
| Pairing/peer state | Verified identity/device fingerprints, pairing transcript reference, accepted scopes, protocol versions, last verified authorization state, per-stream receive cursors and gaps. Trust and authorization are distinct from transport reachability. |

Role labels are local application capabilities. A trainer's “Admin” role means administering their own plans and consented relationships, never a global superuser. See the [permission matrix](ROLES_AND_PRIVACY.md#permission-matrix).

## Training records

| Record | Owner and proposed contents |
| --- | --- |
| Exercise definition/revision | Trainer-owned prescription; stable exercise ID, immutable revision ID, name and instructions. A plan references an exact revision or embeds a minimal immutable snapshot; later edits do not rewrite old prescriptions. |
| Weekly plan | Trainer-owned stable plan ID and metadata. Published plan contents live only in immutable revisions. A draft is local until explicitly published. |
| Plan revision | Plan ID, revision ID, parent revision IDs, author device, ordered day definitions, exercise prescriptions and set targets. Editing a published revision creates a new revision. Parallel descendants are an explicit conflict until the owner publishes a resolution naming all resolved parents. |
| Day/exercise/set prescription | Stable IDs within the revision, ordinal position, exercise reference/snapshot, target sets, repetition count or range, optional load and rest. Distinguish an explicit zero target from an omitted target. Rest days are represented explicitly; ordering is independent of display labels. |
| Assignment/revision | Trainer-owned ID, relationship/client ID, exact published plan revision ID, effective local date range, schedule/time-zone context, status and parents. Never point to “latest plan.” Multiple assignments can overlap only when intentionally accepted; an unintended overlap is surfaced, not silently chosen. Updating a plan does not change an existing assignment. |
| Session/revision | Client-owned session ID, client and relationship scope when shared, optional assignment/plan/day references, start/end instants, recorded zone, status and parents. Unplanned sessions have no assignment dependency. Preserve the performed prescription snapshot when needed to interpret results. Trainers cannot rewrite the client's record. |
| Set result | Belongs to a client session revision; stable set ID, exercise and optional prescribed set references, actual repetitions, load and completion state. Preserve performed order and additional/skipped sets; planned values are not actual values. |
| Body weight/revision | Client-owned measurement ID, body mass and unit, observation instant and zone or explicit date-only observation, parents and sharing scope. Body weight is not implicitly shared. |

Published revisions are immutable. Updates to assignments, sessions, metrics, grants, and editable identity metadata also use immutable revisions with explicit parent IDs. References include required revisions, not just stable roots. Tombstones are owner-authorized records over a root and its known heads; they preserve deletion intent without rewriting history. See [conflict and deletion rules](SYNCHRONIZATION.md#merge-conflicts-and-deletion).

## Measurements and time

- Define canonical interchange units: load/body mass in kilograms, rest in integer milliseconds, and repetitions as non-negative integers. Use fixed-scale decimal values with a documented scale per field, not binary floating-point equality. Presentation can convert to pounds or minutes without changing stored facts.
- Load must identify its meaning: external total load, per-hand load, bodyweight, or assisted load. Do not encode bodyweight as an ambiguous zero or assistance as an unexplained negative number. A measurement kind must specify whether zero is valid; unknown is null, not zero.
- Declare supported metric kinds and unit/range validation in the eventual versioned schema. Reject unsupported kinds or unrepresentable precision instead of guessing or silently rounding.
- Represent event instants in UTC, preserve the recorded IANA time-zone ID and observed offset for presentation. A training day or assignment date is a calendar date with its declared zone, not midnight UTC. Date-only historical body measurements remain date-only.
- Record observation/performance time separately from authoring/export/import time. Manual backdating is allowed and visible. Device time is descriptive, never an authority for ordering concurrent edits, granting trust, or choosing a winning revision.

## Change and receipt metadata

Each exportable change has a stable change ID, owner identity, originating device/stream ID, monotonically increasing stream sequence, record type/root/revision IDs, parent revision IDs, relationship and audience scope, schema version, signed content hash and optional tombstone marker. Hash the canonical serialized representation defined by the protocol, not local SQLite bytes. Immutable signed content cannot be reserialized into a different signature meaning.

- Persist the domain change and its outbox entry in one local transaction. Sequence allocation is transactional; committed changes cannot disappear behind a cursor. A fresh/recovered device uses a new stream, never reuses an old sequence space.
- A stream has a fixed authorization audience/category. Sharing with another peer creates a scoped publication stream/reference while preserving original ownership and signatures. Filtering unrelated records out of a shared global stream must not manufacture unexplained acknowledgement gaps.
- Maintain per-peer, per-stream contiguous applied sequence and sparse received ranges above it. A receipt identifies the receiver and exact stream/ranges; receiving sequence 12 does not acknowledge a missing sequence 11. Maintain durable change-ID/content-hash deduplication independently of transport archive IDs.
- Local storage also tracks unresolved heads, missing dependencies, peer authorization evidence, import/export attempts and deletion retention state. These operational records are not client health data or authority to modify it. Receipts are scoped protocol metadata, not domain revisions.

The [synchronization contract](SYNCHRONIZATION.md) defines dependency closure, atomic import, acknowledgement, tombstone retention and recovery behavior.
