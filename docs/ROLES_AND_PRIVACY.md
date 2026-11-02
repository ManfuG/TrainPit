# Proposed roles and privacy

This document proposes product and authorization rules. They are not implemented security guarantees. See the [data model](DATA_MODEL.md), [synchronization contract](SYNCHRONIZATION.md), and [project README](../README.md).

## Permission matrix

“Admin” is the trainer's local role for managing their own work. It is not a global administrator, cross-trainer role, database bypass, or permission to read all clients.

| Action | Trainer / local Admin | Client |
| --- | --- | --- |
| Manage own identity/devices and encrypted recovery material | Own identity only | Own identity only |
| Create, revise, publish or retire training plans | Own plans only | Read explicitly shared published revisions; cannot rewrite the trainer's plans |
| Assign a published plan | Only to a paired, consenting client in that relationship | Receive/accept the assignment; cannot silently change its prescription |
| Record/edit sessions, weights, repetitions and body metrics | Read only categories and records disclosed by that client | Author own records; choose what is disclosed |
| View client history/charts | Consented relationship partition and selected categories only | Own data, including data never shared |
| Manage relationships and consent | Accept/end own relationships; cannot grant themselves client access | Accept/end own relationships; grant/revoke access to own data |
| Export or import | Own data and explicitly authorized records; scope and recipient required | Own data and explicitly authorized records; scope and recipient required |
| Delete records | Tombstone own records; locally remove imported copies | Tombstone own records; locally remove imported copies |

Local removal of another owner's imported copy is not a signed deletion of their source record. A role or imported role label never overrides ownership, an absent grant, or a revoked relationship.

## Consent boundaries

- Pairing verifies intended peer keys; it is not blanket consent. Show trainer/client identities, verified fingerprints, purpose, record categories and requested direction before either side accepts a relationship or expands its scope.
- Distinguish plan delivery, selected session disclosure, full session history and body-metric disclosure. “Full history” means all authorized records in the chosen scope, not all records on the device. Body metrics require a separate explicit choice.
- Bind each grant to the client/trainer relationship, issuer, recipient and actions. An authorization to read is not authorization to edit, onward-share, or enroll another device. The owner must authorize each sharing destination; a trainer cannot disclose client records to another trainer through an import/export shortcut.
- Each relationship is a separate partition. Disclosure in one relationship never grants access to another client's records.
- Newly paired devices need identity-authorized credentials and relationship consent evidence. A device certificate alone does not broaden a relationship grant. Treat conflicting or missing grant revisions conservatively: no expanded access until explicitly resolved.
- Imports display sender, recipient, scope, content categories and conflicts before explicit acceptance. Archive filenames, phone contacts and QR display names are not authorization evidence.

## Local separation and sensitive data

Each local identity has its own database, key namespace and local files. Under a trainer identity, every client read/write/export carries the relationship partition and validated authorization; UI filtering alone is insufficient. Background work must bind to its intended identity, not whichever identity is currently selected. Logging and crash diagnostics must exclude session content, body metrics, notes, secret keys and raw archives by default.

Charts and aggregates are local derived data. Recompute them after accepted imports or deletions; do not synchronize them or use them to bypass access controls. Cached charts must be scoped and purged along with their source partition. No analytics service or permanent backend is required by this design.

A separate database does not protect against a compromised unlocked device. Future implementation must require local device-credential or biometric unlock before accessing client information, protect keys with Android Keystore, and select maintained storage-encryption libraries and an explicit backup policy. A screen lock alone does not encrypt a database. Ordinary exchanges must never include private identity/device keys. Device backup defaults must not accidentally copy plaintext databases or keys into OS/cloud backup; recovery material belongs in the deliberate encrypted recovery workflow.

## Revocation, deletion and limitations

Revocation stops new exports and acceptance of newly unauthorized data on devices that have learned it. It cannot reach an offline device immediately, recall decrypted archives, erase screenshots, prove deletion of another person's copies, or retroactively make already disclosed facts secret. Surface the last verified authorization state and this limitation before sharing. No permanent central server means no authoritative online check for the latest revocation.

- Ending a relationship disables new disclosure; retain only the minimum trust/tombstone metadata needed to reject stale imports. Make retention of previously received records explicit to the local user; do not present a local purge request as a remote guarantee.
- On learning a device/grant revocation, reject its newly arriving changes unless a separately validated recovery/reconciliation decision permits them. A self-reported creation time cannot prove that a delayed change predates revocation. See [authorization freshness](SYNCHRONIZATION.md#authorization-freshness-and-key-loss).
- An owner tombstone can instruct authorized peers to stop presenting a record, but delivery depends on later exchange. Keep deletion evidence long enough to prevent resurrection by late peers; an arbitrary timeout is not sufficient.
- Revoked peers require new consent and pairing before receiving a fresh baseline. A compromised key requires revocation and key replacement; archive encryption cannot undo earlier disclosure.
- Losing keys without usable recovery material can make encrypted archives and identity recovery impossible. Recreating a display name is not identity recovery. Do not promise support staff can restore trust or decrypt data.

Backup restore, merge-only exchange and identity recovery are different workflows, detailed in [synchronization](SYNCHRONIZATION.md#exchange-versus-backup-and-recovery). None of these rules is implemented by this scaffold.
