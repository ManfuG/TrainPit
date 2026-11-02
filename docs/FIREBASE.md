# Firebase backend

## Deployed project

Provisioned on 2026-11-02 using the authenticated Firebase CLI 15.34.2:

- Project `trainpit-9ab42`, project number `835040273905` (TrainPit).
- Android app `io.github.manfug.trainpit`, display name TrainPit, app ID `1:835040273905:android:64dd9cbc0f7c27fd475050`.
- Public Android SDK configuration: `app/google-services.json`. Its generated `storage_bucket` name is only an identifier; no Storage bucket/service was provisioned.
- Default database `(default)`: Firestore Native, STANDARD edition, Europe multi-region `eur3`, `freeTier: true`; point-in-time recovery disabled.
- Email/password authentication enabled, with `passwordRequired: true`. The CLI's current authentication provisioning reports subtype `IDENTITY_PLATFORM`; **billing remains disabled** with no billing account attached. No paid upgrade, Functions or Storage was enabled.
- Firestore rules and indexes deployed with `firebase deploy --only firestore:rules,firestore:indexes`; rules release: `projects/trainpit-9ab42/rulesets/52672337-9a5b-4f2e-97fd-45e121114367`, released at `2026-11-02T15:35:20.295430Z`. Both collection-scope indexes (`kind/serverAt/__name__` and `kind/subjectId/serverAt/__name__`) were read back as `READY`.

Project/database/authentication/billing/release state was checked through management APIs. Signed-app production verification used three temporary synthetic accounts; all 16 associated Firestore documents and all three Authentication accounts were deleted and their absence checked afterward. No real client records were used. Billing was rechecked as disabled. Free quotas still apply: exhaustion can stop cloud authentication/synchronization; this repository never authorizes enabling billing to bypass them. See [Firebase pricing](https://firebase.google.com/pricing) for current limits.

## Trust and consent

Profiles expose only UID, display name and fixed TRAINER/CLIENT role to authenticated exact-UID lookups; profile enumeration and profile changes are denied. A role is not an administrator privilege. Public signing keys can be looked up by authenticated users. Only their account owner can register them; their identity/key cannot change, revocation cannot be undone, and deleted key identities cannot be reused. Private signing keys never belong in Firebase.

Only the two immutable relationship members can read a relationship. A trainer requests a registered client, the client explicitly accepts, either party can end it, and only the active client can enable body-weight sharing. Ending always clears weight consent. A trainer can re-request an ended relationship only as PENDING with weight consent false; the client must accept again. Display names and member IDs cannot be forged or swapped.

Immutable revisions live under `/profiles/{ownerUid}/entries/{revisionId}`. Clients write only their own sessions/body weight. Trainers write plans only for an active client. Owners retain access to their history; active clients read only their assigned trainer plans; active trainers read only that client's sessions and separately opted-in body weight. Ending or opting out immediately blocks subsequent unauthorized server reads, not already acquired copies. Revisions cannot be overwritten or physically deleted by the app: deletions use signed tombstone revisions. This is not a server data-erasure facility.

Rules bound document fields, IDs, payload size (262144 characters), parent count (100), timestamps and page size (200). Unknown document fields/paths are denied. A registered, unrevoked device is required to upload. Rules validate authorization metadata but **cannot parse the JSON payload or verify ECDSA signatures**. The application must match decoded identity/kind/subject/parents/timestamps to the envelope and verify signatures against Firebase-registered keys before applying records. Archive-supplied public keys alone do not establish identity trust. Firebase administrators/service accounts bypass client rules and remain trusted; keep their credentials private.

Firestore stores readable JSON payloads protected by Firebase access controls, TLS and managed encryption at rest. **This is not end-to-end encryption**: project administrators and the cloud service are in the trust boundary. Android local encryption and encrypted export archives are separate protections. The Android gateway must disable persistent Firestore disk caching so health data does not create an additional plaintext cache outside encrypted local storage.

## Query contract

Use per-owner `entries` collections, ascending `serverAt`, then ascending document ID, `limit(200)`, with the saved timestamp/document-ID cursor. Server timestamps order transport only, not domain conflict resolution.

- Own collection: no kind filter required.
- Client downloading trainer plans: `kind == PLAN` and `subjectId == current client UID`.
- Trainer downloading client sessions: `kind == SESSION`.
- With current body-weight consent: `kind in [SESSION, WEIGHT]` (or a separate WEIGHT query).
- Relationships: `members array-contains current UID`; unfiltered listing is denied.

Rules are not filters: querying a foreign collection without its required scope is denied even if the existing documents happen to be authorized. Composite indexes support these scoped delta queries. Large payload/parent/public-key fields are excluded from indexing. Key listing remains authenticated because verification keys are deliberately public, not health records.

## Reproduce and test

Use Node 24, Java 21+ for the current Firestore emulator, and Firebase CLI 15.34.2 or compatible. From the repository root:

```sh
npm --prefix firebase-tests ci --ignore-scripts
firebase emulators:exec --project demo-trainpit --only auth,firestore 'npm --prefix firebase-tests test'
```

The Node test runner uses pinned `@firebase/rules-unit-testing` and `firebase` dependencies with a lockfile. It refuses to run without `FIRESTORE_EMULATOR_HOST`; all fixture identities/data exist only in the emulator. Authenticated rules contexts simulate ID tokens; the suite covers Firestore authorization, not email delivery or real sign-in. Emulator endpoints bind only to loopback: Authentication 9099, Firestore 8180, Emulator UI 4000; single-project mode is enabled. The `demo-` project prevents accidental production access. Android debug builds made with `-PfirebaseEmulators=true` use `10.0.2.2` to reach these services; release builds always use the real configured backend.

Deploy only the configured free services, after reviewing rules and running the emulator suite:

```sh
firebase login
firebase deploy --project trainpit-9ab42 --only auth,firestore:rules,firestore:indexes
firebase firestore:databases:get '(default)' --project trainpit-9ab42
firebase apps:list --project trainpit-9ab42
```

The SDK API key/app identifiers in `google-services.json` identify the public client; they are not administrator credentials or authorization. Never commit CLI refresh tokens, service-account keys, admin access tokens, private signing keys, password hashes, real user records or exported health archives. Do not log unfiltered Authentication admin config responses: these may include password-hashing secrets. Enable no billing account, paid services, Functions or Storage for this setup.

## Offline and server limits

Initial registration/sign-in, password reset, new-device key registration, first relationship acceptance/key trust and cloud synchronization require the server. Cached authentication/local encrypted records can support subsequent offline use; token expiry, account disablement and revocation cannot be freshly checked while disconnected. Ending a relationship cannot claw back an archive or another device's previously downloaded data. Local authorization must purge foreign records when it learns consent was revoked. Synchronization and archive signatures do not prove a trainer's professional credentials, validate the factual accuracy of workouts, or provide a medical record guarantee.

## Release verification

Verified on 2026-11-02:

| Check | Observed result |
| --- | --- |
| JVM model/archive/revision tests | 17 passed |
| Android Room/Keystore/shared-repository/cloud/account tests, API 26 and 36 | 5 passed per API |
| Firestore adversarial authorization tests | 12 passed |
| Debug and signed release builds; Android lint | Passed |
| Signed production APK, API 36 emulator | Trainer/client accounts, multiple clients, consent/weight opt-in, two-day plan creation/update, actual session sets, correction history, dashboards/charts, selected-session file roundtrip, revocation and local-erasure recovery |
| Signed production APK, API 26 emulator | Cross-installation history/signature recovery, offline save/restart, full-history file roundtrip preserving the pending outbox, reconnection/upload, dark mode and 1.3× system font |
| Hardened archive UI, API 26 debug/emulator backend | Full-history export/import; native file header read back as 600000 PBKDF2 iterations; duplicate import preserved both records |
| Production rules | Trainer session query allowed with active consent; body-weight query denied before opt-in; both denied after connection end |
| Production verification cleanup | 16 exact fixture documents absent; three exact Auth users absent; no unrelated data deleted |

The synthetic dashboard scenario produced two sessions, three completed sets, 25 repetitions and 1300 kg × reps. Selected-session export contained one session and two historical plan revisions, no body-weight records; duplicate import added nothing. The offline full-history roundtrip contained five signed revisions across four records and left its one pending cloud revision pending.

These checks do not constitute a physical-phone or independent security audit. API 26 and 36 were exercised; API 37 and intermediate API levels were not individually run. Hardware-backed key protection depends on the actual phone. CI runs build/lint/JVM and Firestore-rule checks; connected Android and native file-picker checks require an emulator/device.
