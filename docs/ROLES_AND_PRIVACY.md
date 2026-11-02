# Roles, ownership and privacy

These are the implemented access rules and their limits, not a claim of regulatory compliance or protection from a compromised operating system. See [data models](DATA_MODEL.md), [synchronization](SYNCHRONIZATION.md) and [Firebase deployment](FIREBASE.md).

## Permission matrix

“Personal Trainer / Admin” administers the trainer's own work and accepted client relationships. It is never a global administrator.

| Action | Trainer / Admin | Client |
| --- | --- | --- |
| Publish/update a weekly plan | Own plan, assigned to an active consenting client | Read assigned published revisions; cannot edit the trainer's plan |
| Record/correct sessions and actual sets | Read consented client results only | Own sessions, repetitions and external training loads |
| Record/correct body weight | Read only with that client's separate body-weight consent | Own measurements; control disclosure |
| View dashboards/charts | Selected consenting client partition | Own records and assigned plans |
| Connect | Request using the client's exact account UID | Review and accept/decline; weight sharing initially off |
| End connection | Own relationship only | Own relationship only |
| Import/export | Own and currently authorized records for the selected client | Own history and currently authorized plan dependencies |
| Delete | Signed deletion markers for own plans | Signed deletion markers for own sessions/body weight |
| Erase device cache | Only this account's local database/keys; signs out | Same |

Clients can accept independent trainer relationships. Consent in one relationship never exposes another client's data. Trainer-written plans cannot overwrite client-owned results; role labels imported in files cannot change account identity or ownership.

## Consent and identity

Firebase email/password authentication supplies stable account UIDs. Public exact-UID profile lookup reveals only display name and fixed role; profile enumeration and profile changes are denied. Display names and role selection do not verify professional qualifications or prove the real-world person behind a UID. Share account IDs through a trusted channel and check both the name and UID when accepting a connection.

Client acceptance grants session-history access while the connection is active. Body-weight history requires a separate explicit switch and confirmation. Either person may end the relationship; ending clears body-weight consent. A trainer can re-request only as pending, requiring fresh client acceptance. Connection/scope changes need internet access; an offline client is never silently treated as consenting to new access.

Archive preview verifies current locally known scope, account/client binding, signed records and authenticated registered public keys. An included public key, filename or password alone cannot grant access or replace account keys.

## Local protection

- Separate Room database and Android Keystore key namespace per account. Trainer client queries/imports/exports are scoped by subject and accepted relationship.
- Sensitive record contents and local profile/relationship/key/cursor metadata use AES-256-GCM. Opaque IDs, kinds, pending flags and counts remain SQLite index metadata; this is row encryption, not a fully encrypted SQLite file.
- A phone PIN, password or pattern is mandatory before displaying authenticated records. Reauthentication occurs after 30 seconds in the background or activity recreation. `FLAG_SECURE` protects app windows/recent-task previews where Android enforces it.
- Passwords, archive secrets and health drafts are memory-only, not SavedState bundles. Drafts/archive operations survive configuration changes and credential relocking within the same process; process death can discard unsaved work. File-picker navigation keys are retained separately from secrets.
- Sign-out clears visible account state in every active controller, cancels its background work and closes its repository reference. Existing encrypted records remain for a later authenticated login.
- Explicit local erasure signs out and invalidates all shared UI/worker references before deleting database/WAL/SHM and the account encryption/signing aliases. Unsynced records are lost unless an independently usable export exists. This never erases cloud data or another device.
- OS/cloud backup and device-transfer extraction are excluded. Firebase Authentication maintains its own session state under the Android application sandbox; the app does not claim its SDK cache is protected by Room row encryption.

These controls do not protect an unlocked/root-compromised device, malicious accessibility service, independently photographed screen or stolen account credentials. Private signing keys do not leave Android Keystore; hardware backing depends on the phone.

## Cloud protection and free-tier limits

Firebase rules enforce account ownership, fixed roles, consented reads, immutable revisions and registered unrevoked device metadata. HTTPS/TLS and Google's managed encryption protect transport/storage. **Cloud record JSON is not end-to-end encrypted**: Firebase/project administrators and service-account holders are trusted processors and can bypass client rules.

There is no Analytics, advertising or Crashlytics integration. App code does not log health payloads, archive secrets or raw account credentials. Do not put real client histories in issue reports, test fixtures, screenshots or build logs. The public Android SDK configuration is not an administrator credential; private CLI tokens, keystores and passwords stay outside Git.

Billing is disabled. Free-tier limits can interrupt cloud access; they do not authorize enabling billing or dropping local pending changes. There is no application promise of continuous cloud availability, instantaneous remote synchronization, medical-record accuracy or legal compliance.

## Revocation and deletion

The server immediately denies new reads once it knows a relationship has ended or weight consent was removed. On learning the change, an app purges unauthorized imported records and resets receive cursors so later renewed consent can reseed safely. Synchronization is single-flight within a process so a concurrent worker cannot overwrite newly learned consent with an older snapshot.

An offline device cannot learn an unseen revocation immediately. Revocation cannot recall plaintext already viewed, archived files, screenshots or copies retained outside the app. Previously trusted device keys may be revoked in the backend; the app rejects newly received revisions signed under a known revoked key. It cannot prove that an old claimed authoring timestamp predates compromise.

Record deletion is **logical deletion**: signed tombstones hide active records and prevent old imports from silently resurrecting them. Historical revision payloads and cloud copies remain; full-history exports include retained revisions/deletion evidence. There is no client-side cloud hard-erasure or account-deletion facility. Physical server erasure requires an explicit project-administrator process and must account for distributed copies; do not represent the delete button as that guarantee.

## Recovery limits

A new installation can sign in, enroll a new device key, download authorized cloud history and cache registered verification keys. Encrypted archives can merge authorized signed history after identity/relationship/key trust is available; they are not OS backups and contain no private keys or account passwords.

Local key loss does not trigger a destructive reset. Preserve the affected database and recover a verified cloud/archive copy on a clean installation. A missing archive passphrase, unregistered/lost signing identity, unavailable account authority or never-exported/never-uploaded edits can make recovery impossible. Firebase password reset restores account access, not erased local keys or a forgotten archive passphrase.
