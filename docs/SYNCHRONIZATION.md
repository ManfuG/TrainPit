# Synchronization

TrainPit uses encrypted Room records as its working source of truth, Firebase Authentication as account authority, and Firestore as a managed exchange service. Encrypted files provide manual exchange without a live server connection after account/relationship/key setup. There is no custom always-on server, live SQLite-file replication, CRDT framework, Functions service or Storage bucket.

See [models](DATA_MODEL.md), [ownership/privacy](ROLES_AND_PRIVACY.md) and [Firebase configuration](FIREBASE.md).

## Identity and consent bootstrap

1. Register/sign in online and select a fixed account role. Each account installation creates nonexportable Android Keystore encryption/signing keys.
2. Synchronization registers the device's public signing key under its authenticated Firebase UID. Other devices cache these authenticated registrations; fingerprints alone or archive-provided keys never establish identity.
3. The client shares their exact account UID through a trusted channel. The trainer requests that client; the client verifies the displayed name/UID and explicitly accepts. This enables session-history sharing; body weight remains a separate opt-in.
4. Synchronize both devices before relying on offline file exchange. Newly enrolled devices, new relationships, scope changes and uncached signing keys require online verification.

The account service is a real dependency for initial setup and new trust decisions. This implementation does not offer anonymous/offline account creation, QR identity enrollment, nearby transport or self-hosted account recovery. Ordinary local use and already-authorized file exchange do not require a continuously reachable backend.

## Local changes and ownership

Every edit creates a signed immutable `Entry` revision. A Room transaction commits the revision and pending-upload flag together. Clients author only their own sessions/body weight; trainers author their own plans for an accepted client. The subject, kind and owner cannot change across a root's history.

Published plan updates create descendants. Session records retain the exact performed plan-revision ID plus their own exercise names/results; a later plan edit, revoked relationship or unavailable old plan cannot rewrite those performed facts. A plan reference is an optional historical annotation, not authority to fetch another trainer's private plan.

Different devices may edit the same root concurrently. Complete same-root parent ancestry identifies descendants; wall-clock timestamps, device priority and arrival order never choose a winner. Multiple heads remain visible as conflicts, excluded from ordinary statistics. Only the owner publishes a resolution naming every head; more than 100 heads are resolved through an atomic bounded chain.

## Cloud synchronization

`FirebaseGateway.synchronize` is single-flight across foreground/background adapters in the process. The sequence is:

1. Verify the active Firebase UID matches the local repository and register/validate its device key.
2. Fetch current relationships. Purge unauthorized foreign records when consent ends or body-weight scope is removed. Scope changes clear receive cursors so renewed grants reseed rather than skip previously hidden categories.
3. Cache authenticated signing keys for the account and active peers. Known revoked keys cannot be silently reactivated or replaced.
4. Scan the indexed pending outbox in pages of 100. Group transactions by author device and client, keeping Firestore rule-access counts bounded. Read existing revision IDs before writes: matching signed payload means an already committed retry; different contents are a collision. New documents receive authoritative `serverAt` timestamps and cannot be updated/deleted by client rules.
5. Mark those exact local revision IDs uploaded only after the server transaction succeeds. File creation, a queued SDK write or another person's receipt is never treated as a cloud acknowledgement. Trainer plan updates for ended relationships stay local/blocked rather than preventing other clients' updates.
6. Pull own history and each currently authorized peer/category in pages of at most 200, ordered by `serverAt` and document ID. The cursor includes timestamp seconds/nanoseconds plus ID, so equal timestamps do not skip records.
7. Match decoded payload to its cloud envelope, validate original owner/device signatures, and fetch missing same-owner parents through an iterative bounded dependency walk. Apply valid revisions and the next source cursor in one Room transaction. Failure rolls back that page and leaves the cursor unchanged.

Own collections are account-scoped. Clients query trainer plans by `kind == PLAN` and their own subject UID. Trainers query the client's SESSION category and, only with consent, WEIGHT. Firestore rules are not filters; broader foreign queries are denied. Composite indexes support these exact queries.

Firestore's persistent disk cache is disabled: only encrypted Room is durable on-device. Immutable revision contents are cached after validation; unchanged relationship/key responses do not rewrite/decrypt the entire local history. Query cursors and pending flags survive process restarts.

Synchronization is automatic. Foreground checks run when unlocked/visible and approximately every minute; edits request a background-safe synchronization attempt. **Settings** retains an optional manual sync/retry action. Network-constrained WorkManager runs roughly hourly, starting after an initial hour rather than duplicating launch synchronization. Android scheduling, connectivity and free quotas can delay delivery. This is eventual synchronization, not a real-time or always-complete remote view: initial/reseed pulls may show partial history until the current sync finishes.

## Encrypted file format

The app writes `.trainpit` files through Android's document picker. Version 1 is:

```text
TPARCH01 (8 ASCII bytes)
PBKDF2 iterations (4-byte big-endian integer, exactly 600000)
salt (16 random bytes)
GCM nonce (12 random bytes)
AES-256-GCM ciphertext of UTF-8 ArchiveManifest JSON, followed by its 16-byte tag
```

PBKDF2-HMAC-SHA256 derives the AES key from a user-provided passphrase of at least 12 characters, using the [OWASP PBKDF2 work-factor baseline](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2). The complete 40-byte header is authenticated associated data. Java/Android standard cryptographic APIs supply the primitives and randomness; no custom cryptographic algorithm is used. Keys/plaintext byte buffers are cleared where possible; immutable UI strings cannot be promised forensic erasure.

The encrypted manifest contains format/archive ID, declared exporter UID, client subject UID, export time/mode, original signed revisions and their public verification keys. Record signatures establish **record authorship**, not the identity of whoever forwarded the file. The exporter field is informational; there is no separate exporter signature or recipient-specific public-key encryption. Anyone holding the archive passphrase can decrypt/repackage it. Share the password separately and never treat the password or declared exporter as a new access grant.

Limits: 32 MiB encoded file, 50000 revisions/device keys, JSON nesting at most 32, supported entry schema 1, and bounded per-record metadata. Unsupported versions, wrong passwords, corrupt authentication tags, malformed UTF-8/JSON, excessive sizes and invalid signatures are rejected. This is a single encrypted JSON payload, not ZIP extraction or executable content.

## Export, preview and atomic import

- **Full client history:** all retained, locally authorized revisions and tombstones for the selected client. A trainer cannot export another client's partition or body-weight history without its consent.
- **Selected sessions:** selected session roots and their complete revision ancestry, plus available/authorized exact plan dependencies and plan ancestry. Unrelated sessions and body-weight measurements are excluded. Unavailable external plan references remain opaque annotations; performed results stay self-contained.
- No file includes private signing/encryption keys, account passwords or Firebase refresh tokens. Export never clears an outbox or advances cloud acknowledgement state.
- Import is merge-only into the currently authenticated account's allowed client scope. It cannot change account identity, enroll keys, replace a database, overwrite revision contents, remove known conflict branches or roll back a retained tombstone.
- Decryption/preview validates archive bounds, subject consistency, dependencies, cached trusted keys, signatures, role/consent and ID collisions before showing counts. The user explicitly confirms import. Commit revalidates current permissions and merges atomically; duplicates do not create additional facts. Newly recovered own revisions enter the account's outbox; foreign revisions never become locally authored uploads.
- Draft secrets, selected URI and operation state live only in a ViewModel. Android activity-result/navigation keys survive credential relocking separately, so a long file-picker visit does not lose the pending import/export. Process death can still interrupt an unsaved operation. A failed destination write may leave an incomplete external file; authentication prevents that file being accepted as valid history.

An archive cannot prove that it contains all records ever created or every later deletion. “Full” means all retained authorized local history at export time, not a recovery of erased/never-received data. Complete an online baseline on a new/reconsented device before assuming its history is current. Offline import cannot discover deletion/revocation evidence that the device has never received.

## Deletion, revocation and recovery

Tombstones retain deletion intent and causal history; the app does not prune them by elapsed time. Replayed ancestors cannot override a retained deletion head. Concurrent delete/edit branches require owner resolution. Local removal of another owner's imported copies is not an author-signed deletion of their records.

Server authorization changes block subsequent unauthorized server reads immediately. Informed devices purge their now-unauthorized imported copies. Offline recipients cannot learn unseen revocations or be forced to erase previously disclosed files/data. Cloud JSON uses TLS/access control/managed encryption, not end-to-end encryption; administrators remain trusted.

A new installation signs in, enrolls a fresh device key and downloads authorized cloud records/registered verification keys. Original private keys and old sequence identities are not restored or exported. A valid encrypted archive can recover unsynced history only when its original device key can be authenticated and the current account/relationship permits the data. A never-registered lost key, forgotten file passphrase or missing unsynced copy may be unrecoverable. Known revoked-key histories fail closed; compromised-key reconciliation is not inferred from claimed creation times.

Local erasure is explicit cryptographic/device-cache removal; it is not a cloud/account hard-erasure workflow. Historical server revisions remain unless the project administrator performs a separate deliberate erasure process. See the [privacy limits](ROLES_AND_PRIVACY.md#revocation-and-deletion).
