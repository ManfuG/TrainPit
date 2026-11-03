# Roadmap

The initialization has been replaced by a working native Android application. The delivery phases below map to [the issue checklist](ISSUES.md); verification covers synthetic data, not real client records.

## 1. Roles and offline workflows

Implemented fixed trainer/client accounts, multiple consenting clients per trainer, client-owned results, and offline use after initial online account setup. Trainers do not receive project-administrator privileges.

## 2. Secure local storage

Implemented per-account Room databases, Android Keystore row encryption and signing keys, shared transactional repository instances, restart persistence, and explicit local erasure. Backups/device transfer are disabled; unavailable keys or invalid data fail explicitly rather than silently resetting storage.

## 3. Weekly training plans

Implemented one trainer-owned plan per client/week, immutable corrections, current-week-only schedules, owner-signed automatic carry-forward and offline inherited viewing. Deleted/conflicted slots do not repeat; historical session references remain exact.

## 4. Logging and body metrics

Implemented compact exercise-grouped kg/reps entry, explicit last-set copying, one body-weight record per date, corrections and logical deletions. Repeated daily entry updates its existing root. Date pickers and displayed dates include weekdays; prescribed targets never become actual results automatically.

## 5. Dashboards, charts, and statistics

Overview focuses on today's workout and selectable body-weight chart points. History & stats uses distinct record-type colors, same-row filters and ID-free review. Session details chart daily maximum load and total reps per exercise across authorized current session heads. Trainer monitoring remains read-only and consent-scoped; missing data is not invented.

## 6. Synchronization and history import/export

Implemented managed Firebase Auth/Firestore synchronization plus password-encrypted portable files; no self-hosted permanent server is required. Initial identity, consent and signing-key trust require connectivity. Immutable signed revisions, durable cursors, idempotent application, explicit conflicts, complete authorized histories and selected-session dependencies are documented in [SYNCHRONIZATION.md](SYNCHRONIZATION.md).

## 7. Privacy, access, and recovery

Implemented explicit connection acceptance, body-weight opt-in, revocation, credential-gated access, protected app windows, authorized export, logical history deletion, and account-local erasure. Cloud payloads are not end-to-end encrypted. Revocation cannot erase previously received copies; recovery cannot restore unsynchronized records without an existing encrypted export. See [ROLES_AND_PRIVACY.md](ROLES_AND_PRIVACY.md).

## 8. End-to-end validation and release

Release gates: JVM behavior tests, Android persistence/account/cloud workflow tests, adversarial Firestore-rule tests, Android lint, signed release verification, native device workflow checks, and a downloadable APK with a checksum. [FIREBASE.md](FIREBASE.md) records backend verification; [README](../README.md#development-setup) provides reproducible commands.

API-level and physical-device coverage must be stated with each release rather than inferred from successful compilation. Dependency/security updates and reported defects continue through focused issues; no additional product features are committed by this roadmap.
