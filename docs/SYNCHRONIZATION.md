# Proposed synchronization

TrainPit is proposed as an offline-first application with no required backend or permanent central server. This is an implementation decision contract, not working synchronization or a security guarantee. No archive format, cryptographic dependency, key storage or transport is implemented. See the [data model](DATA_MODEL.md), [privacy rules](ROLES_AND_PRIVACY.md), and [project README](../README.md).

## Baseline and transport

The initial interchange is a portable, encrypted, versioned `.trainpit` archive explicitly exported and imported through Android file/document sharing. This is the baseline, not physical SQLite replication. Never exchange a live database, database journal or Room schema as a synchronization protocol.

Optional nearby transport can later exchange the same envelopes and receipts after equivalent peer verification and consent. No custom CRDT framework or mandatory cloud relay is planned. WorkManager may schedule bounded opportunistic work when a usable transport, authorization and Android constraints allow it. It cannot bypass background restrictions, create connectivity, guarantee delivery, or provide guaranteed remote real-time synchronization. Manual exchange remains usable offline.

## Pairing and trust bootstrap

1. The initiating device shows a short-lived QR invitation containing protocol version, identity/device public-key fingerprints, a fresh unpredictable challenge, requested relationship/scopes and invitation identifier. The QR contains no private keys or reusable bearer grant. Large certificates can travel in the handshake and must match the QR fingerprints.
2. The responding device verifies protocol support and certificate chains, generates its own challenge and presents its identity/device keys and requested scopes. Both devices sign a canonical pairing transcript binding both identities, devices, challenges, invitation, protocol and proposed scopes. QR scanning alone does not complete pairing.
3. Both people compare a transcript-derived verification code/fingerprint over an independent, trusted channel (in person is sufficient), verify the intended counterpart, and explicitly accept the relationship and scopes on each device. A screenshot, unverified remote QR, or matching display name is not proof of the intended person. Derivation and handshake must use a vetted protocol/library, not an invented cryptographic scheme.
4. Persist the accepted transcript, pinned public keys, authorized device certificates and scoped grant evidence. Mark the one-use invitation consumed and expire abandoned handshakes. A substituted root key or new device requires an explicit authenticated enrollment/recovery decision, never silent trust-on-import.

Pairing establishes a peer, not unrestricted access. Consent to exchange body metrics or history is separate. Root-authorized own devices may share ownership, but must still carry current relationship scope. Conflicting identity/grant evidence blocks trust expansion.

## Versioned archive envelope

Before implementation, choose maintained audited libraries and a standard authenticated-encryption/signature container, document the exact algorithms, canonical serialization, key wrapping, resource limits and compatibility rules in a reviewed format specification. Do not design new primitives. Use library-generated fresh randomness/nonces and recipient-bound encryption; ordinary exports never contain private keys. A filename extension is only a user hint.

The proposed envelope has two levels:

- A small bounded outer header carries format/version, cryptographic suite identifier, archive ID, recipient key identifier(s) and encrypted payload framing. Minimize exposed metadata; health data and readable manifest stay encrypted. Treat header fields as untrusted until authentication succeeds; bind relevant header bytes as authenticated associated data under the chosen standard.
- An encrypted, signed manifest binds the archive ID, sender identity/device and certificate references, intended recipient identity/device keys, relationship/grant scope, payload schema versions, export mode, stream ranges, dependency inventory, payload hashes/counts and optional creation/expiry metadata. Creation time is descriptive, not a merge clock. Each included record/change retains owner/device provenance so forwarding does not make the exporter its author.

Use a recipient-specific export by default. A multi-device or multi-recipient export must enumerate every recipient and validate scope for each; it cannot mean “anyone with the file.” A sender signature alone is insufficient: verify authorization for each record, signed dependency and receipt. A payload digest alone is neither encryption nor proof of authorship.

Reject unknown mandatory versions, unsupported algorithms and invalid signatures, rather than best-effort reinterpretation. Define bounded record sizes, total expanded size, entry count, compression ratio, nesting and filename handling; reject path traversal and unsafe extraction. No arbitrary executable content, file URL fetching or automatic attachment execution is part of import.

### Explicit acceptance and atomic import

1. Open into bounded app-private staging. Parse defensively; authenticate/decrypt with an intended local recipient key. Verify signature chains and recipient identity/device binding against the active local identity. Never offer a wrong-identity archive to an unrelated local database.
2. Validate manifest, hashes, schema/units, unique IDs, sequence consistency, ownership, relationship scope, known revocations, dependency closure and parent references before changing domain state. Reusing a change/revision/stream-sequence ID with different signed contents is a hard error. A forged or missing dependency is not a default value.
3. Present the verified sender/recipient, categories, record counts, requested scope changes and expected conflicts. Require explicit acceptance for this import; accepting data does not automatically accept a broader grant or new trusted key. Previously committed duplicate archives can show their prior receipt rather than reapplying changes.
4. Apply all accepted records, immutable conflict heads, tombstones, deduplication entries, outbox consequences and receive cursors in one local database transaction. Stage file-backed content first and commit durable references only after all files are complete; roll back domain state on failure. A rejected or incomplete archive gets no applied receipt. Retain staging only for an explicit retry and clean it safely.
5. Only after commit, issue a signed receipt bound to the receiver, sender/stream and committed change ranges. Imported data may produce visible unresolved conflicts without failing atomicity; invalid data may not partially apply.

Replay protection is layered: recipient binding, pairing challenge consumption, stable archive IDs, durable change IDs/hashes and stream-sequence validation. Re-importing identical committed content is a no-op with the same acknowledgement meaning. A different archive containing an old accepted change is also a no-op; changing its archive ID does not evade deduplication or a tombstone. Keep compact replay/deletion evidence when payloads are pruned.

## Export scope and dependencies

Offer two explicit exchange modes: full authorized history for a chosen relationship/category, or selected sessions. Both are merge-only exchanges, not backup/restore.

- Compute the transitive dependency closure: selected session revisions and set results, relevant assignment and exact plan/day/exercise revisions or authorized immutable snapshots, parent revisions needed for conflict interpretation, owner/device certificates, applicable grants and deletion evidence. Include only data necessary and authorized for that recipient.
- Selected sessions do not imply all body metrics, other sessions, other clients, drafts, private notes or private keys. A referenced object must have an authorized exportable representation; if none exists, block the export and explain the dependency rather than leak it or emit a dangling reference.
- “Full history” includes retained revisions and tombstones inside the selected consent scope. It does not claim to recover history that was lawfully compacted, deleted locally, or never received. Show this limitation before export.
- Split large histories into bounded, independently valid pages with dependency closure or references to recipient-acknowledged dependencies. Never require committing an incomplete page. A manifest may group pages, but each committed archive must stand alone against known recipient state.
- Receipts are portable, signed, recipient-bound protocol records and can return in a later archive; there is no assumed network return channel. Sending a file does not imply it was imported.

## Delta exchange and receipts

Use an append-only outbox per originating device and fixed audience/category stream. Allocate monotonic sequences transactionally with domain changes; rollover or device replacement creates a new stream. Preserve original author signatures if an authorized peer relays records. The relay cannot manufacture an owner's changes, authorization or receipts.

Maintain receiver state separately for each peer and stream:

- A contiguous applied cursor covers only the complete prefix; sparse applied ranges above it preserve holes. Applying 10 and 12 while 11 is absent yields cursor 10 plus range 12, not cursor 12. A later 11 closes the gap. Duplicate sequences must match the original contents.
- A selected-session archive can leave holes intentionally. Its receipt acknowledges only applied ranges; it never pretends that filtered-out changes were received. Streams scoped to a different audience do not share acknowledgement state. Scope expansion publishes newly authorized history explicitly rather than silently treating it as already acknowledged.
- Validate receipts against that peer's identity, authorization and records actually published to it. A receipt from client A cannot advance client B's cursor. One peer's receipt cannot delete updates needed by another peer or an offline authorized device.
- Unsent and unacknowledged updates remain discoverable until the relevant peer acknowledges them or that peer is explicitly retired/reseeded under the retention policy. Do not clear an outbox merely because an export file was created. A receipt acknowledging retained conflict branches means durable receipt, not conflict resolution.

Index outbox by audience/stream/sequence, changes by unique ID/hash, revisions by root/parents, dependencies by referenced revision, and receipts by peer/stream/range. Query bounded pages after the cursor plus requested holes; cap bytes, records, transaction duration and dependency expansion. Do not load an entire history or scan every client's partition for each exchange. Persist pagination state and retry from committed boundaries. Derived chart data is excluded.

## Merge, conflicts and deletion

Single-author roots mean one owning identity, not one writable device. Two authorized devices can concurrently edit an assignment, session, metric or plan. Every revision names its parent revision IDs; revision ancestry, not timestamps or import order, determines causality.

- A descendant can advance a root's head when all required parents are present. Identical revisions deduplicate. Divergent heads remain explicit unresolved conflicts; retain both branches and identify their provenance. No last-writer-wins by wall clock, arrival order or device priority.
- Only the owning identity can publish a resolution revision naming all heads it resolves. Another party can flag a conflict but cannot rewrite ownership. No generic automatic field merge or CRDT framework is assumed.
- A published plan revision remains immutable; resolving a newer plan does not mutate existing assignments or historical session references. Assignment conflicts must not silently choose a different training prescription. Preserve performed facts even when their referenced plan is retired.
- A tombstone is a signed owner-authored change with root ID, known parent heads, scope and deletion intent. It prevents replayed ancestors from restoring a deleted record. An edit concurrent with deletion is an explicit delete-versus-edit conflict, hidden from normal active presentation until owner resolution; import order cannot resurrect it. Any restoration requires a deliberate owner revision resolving the tombstone and competing heads, not an old archive replay.
- Keep tombstones, deduplication summaries and sufficient causal metadata until all relevant authorized peers acknowledge them. An indefinitely offline peer prevents ordinary safe pruning. Time alone is not evidence that deletion reached it.
- To bound retention, explicitly retire stale peer/stream membership and record a new baseline/retention epoch. An old peer cannot resume incremental exchange across that boundary. Require renewed pairing/consent and a recipient-specific reseed that includes current deletion fences and conflict heads; block old-epoch deltas. Re-pairing does not authorize resurrection from its old local database. The returning peer must reconcile its local unsent records explicitly before switching baseline.

Deleting imported payloads locally must preserve enough minimal deletion/replay evidence to avoid accidental restoration. These records and trust metadata have their own documented retention policy. Tombstones communicate deletion; they cannot remotely erase previously decrypted copies.

## Authorization freshness and key loss

Validate each archive against locally known device/grant state, not just the grant included by its sender. Apply restrictive revocation evidence before permitting data that depends on it. Conflicting authorization heads fail closed for new access; they are not resolved by timestamp. Optional expiry can bound use under stated clock assumptions, but clocks and offline expiry cannot prove that a grant is the latest.

An offline recipient cannot know about an unseen revocation. Show last verified state and require explicit acknowledgement of stale authorization risk on offline acceptance/export. Once revocation is known, deny newly arriving changes signed under the revoked authority; a signed historical timestamp does not prove pre-revocation authorship. Already accepted records need an explicit retention/reconciliation decision, not silent historical rewriting. Ending a relationship stops future disclosure on informed devices, not copies already held elsewhere.

Lost or compromised device: retire its certificate from a still-trusted identity device/recovery root, rotate affected keys, notify peers at next exchange and re-pair as necessary. Root-key compromise needs an independently verified new trust root; possession of the compromised root is insufficient proof. Lost recipient keys can render old archives undecryptable. Without usable recovery material, create a new identity and obtain fresh relationships; never impersonate the old identity by reusing its label.

## Exchange versus backup and recovery

| Workflow | Required semantics |
| --- | --- |
| Ordinary import/export | Merge-only into the intended existing identity and relationship; never clear tables, replace keys or reset receipts. Accept scoped, authorized records and preserve tombstones/conflicts. |
| Encrypted backup | Separate explicitly labelled artifact/version covering the chosen identity's full retained local records, trust state, causal/deletion/receipt metadata and a deliberate key-recovery policy. It is not a shareable trainer/client archive. Protect with reviewed library-supported recovery encryption and an independently stored recovery secret; explain that exported plaintext cannot be recovered securely by a forgotten password reset. |
| Restore | Explicit destructive local operation, separate from import, with identity verification and user consent. Restore only to an empty/replaced identity store; do not roll back a live store's monotonic security state through ordinary merge. Treat backup receipts/authorization as potentially stale, obtain current peer state before new sharing, and prevent replay across retention epochs. |
| Identity recovery/new device | Recover identity authority only from valid encrypted recovery material or an already authorized device using a reviewed enrollment flow. Issue a new device ID/keys/stream; never resume a restored device's old sequence counters. Verify with peers, reconcile unsent backup changes by stable revision/change IDs, and re-publish only authorized missing records without double-counting. |

Android keystore-bound keys may be non-exportable and cannot be assumed recoverable on another phone. Decide and document which identity recovery secret can be backed up and how it is protected before implementing backup. A backup that omits private recovery authority is a data backup, not an identity-recovery promise. No support service, permanent server or cryptographic backdoor is assumed.
