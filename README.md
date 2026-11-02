# TrainPit

Offline-first Android application connecting personal trainers and their clients. Encrypted local storage is the working source of truth; authorized records synchronize through Firebase or encrypted files.

Repository: [ManfuG/TrainPit](https://github.com/ManfuG/TrainPit).

## Status

Native Android application, version **1.0.0**, minimum **Android 8.0**. Download the signed APK from [GitHub Releases](https://github.com/ManfuG/TrainPit/releases). If you installed the old repository-setup debug APK, uninstall it before installing the signed release.

Create an account online and choose **Trainer** or **Client**. The role is fixed for that account. A phone PIN, password or pattern is required to unlock client information. Existing local plans and records remain available offline after sign-in.

## Features

- **Personal Trainer (Admin):** connect with multiple clients, publish and update weekly plans, and review consented session results and progress. Admin is relationship-scoped, never global access.
- **Client:** view assigned plans; record and correct sessions, actual set weights/repetitions, and body weight.
- **Dashboard:** weekly schedules, session/set/repetition/external-load statistics, eight-week session charts, and body-weight trends.
- **Local storage:** separate encrypted Room databases per account, durable offline changes, immutable history and explicit conflict resolution.
- **Data portability:** encrypted complete-client-history or selected-session export; authenticated, previewed, atomic, duplicate-safe import.
- **Privacy:** explicit client acceptance; separate body-weight consent; phone-credential unlock; protected windows; account-scoped sign-out and local erasure.

To connect, the client copies their account UID from **Settings**. The trainer enters it under **Clients**; the client reviews the request under **Trainers** and accepts. Body weight stays private until its separate sharing switch is enabled.

## Technology stack

| Area | Decision |
| --- | --- |
| Platform | Native Android; minSdk 26, compile/target API 37 |
| Language | Kotlin 2.4.21; Java/JVM target 17 |
| Build | AGP 9.5.0, Gradle wrapper 9.6.0, Kotlin DSL and version catalog |
| UI | Jetpack Compose/Material 3; ViewModel, coroutines and StateFlow; native Canvas charts |
| Persistence | Room 2.8.5/KSP; Android Keystore AES-256-GCM encrypted payloads and metadata |
| Accounts/cloud | Firebase Authentication email/password and Firestore; billing disabled |
| Exchange | kotlinx.serialization; ECDSA P-256 signed revisions; AES-256-GCM/PBKDF2 encrypted archives |
| Background work | Network-constrained WorkManager; foreground, manual and post-edit synchronization |

Versions are pinned in `gradle/libs.versions.toml`. AGP supplies [built-in Android Kotlin support](https://developer.android.com/build/migrate-to-built-in-kotlin); no separate Android Kotlin plugin or dependency-injection framework is used.

## Architecture

Small modular monolith with unidirectional UI state. `app` connects persistence and Firebase through `LocalRepository`; cloud code never opens database files. Updates exchange immutable domain revisions, not SQLite snapshots.

| Module | Responsibility | Allowed project dependencies |
| --- | --- | --- |
| `app` | Screens, account controller, local unlock, composition and WorkManager | All three core modules |
| `core:model` | Serializable domain records, ownership rules, validation and repository API | None |
| `core:data` | Encrypted per-account Room storage, device signing, revision validation and archive codecs | `core:model` |
| `core:sync` | Firebase accounts, consented relationships, registered public keys and paginated delta exchange | `core:model` |

```text
app/src/main/                   # Launcher, Compose screens, controller and security gate
app/src/androidTest/            # Auth/Room/Firestore cross-role workflows
core/model/src/                 # Domain and invariant tests
core/data/src/                  # Persistence, cryptography, archives and regression tests
core/data/schemas/              # Versioned Room schema
core/sync/src/                  # Firebase adapter
gradle/                         # Pinned versions and checksum-verified wrapper
.github/                        # CI and contribution templates
firebase-tests/                 # Firestore authorization tests
firestore.rules                 # Deployed authorization boundary
firestore.indexes.json          # Scoped delta-query indexes
docs/                           # Data, privacy, synchronization and delivery documentation
```

Immutable revisions are decoded once into the shared account repository, not decrypted on every recomposition. Outbox and cloud reads use bounded pages; writes and imports are transactional. Dashboards derive aggregates from locally authorized records. Add feature modules or caches only for a demonstrated boundary or bottleneck.

## Development setup

1. Install Android Studio with AGP 9.5 support, JDK 17, SDK Platform **37.0**, and Build Tools **36.0.0**. Set `ANDROID_HOME` or an untracked `local.properties` SDK path.
2. Clone and check:
   ```sh
   git clone https://github.com/ManfuG/TrainPit.git
   cd TrainPit
   ./gradlew :app:assembleDebug :core:model:test :core:data:testDebugUnitTest lintDebug
   ```
3. The included `google-services.json` is public client configuration for `trainpit-9ab42`, not an administrator credential. Use local emulators for development and tests; do not create real client fixtures in the deployed project.

### Emulator and regression checks

Start an Android virtual phone and set a device screen-lock PIN. With Node 24, Firebase CLI 15.34.2 and Java 21+ for the Firebase emulators:

```sh
npm --prefix firebase-tests ci --ignore-scripts
firebase emulators:exec --project demo-trainpit --only auth,firestore 'npm --prefix firebase-tests test'
```

For Android integration tests, keep Firebase emulators running in another terminal:

```sh
firebase emulators:start --project trainpit-9ab42 --only auth,firestore
./gradlew :app:assembleDebug -PfirebaseEmulators=true
./gradlew :core:data:connectedDebugAndroidTest -PfirebaseEmulators=true
./gradlew :app:connectedDebugAndroidTest -PfirebaseEmulators=true
```

The explicit debug property routes Authentication to `10.0.2.2:9099` and Firestore to `10.0.2.2:8180`. Release builds always use the deployed backend. Integration tests refuse production fixtures. Ordinary debug builds use the real backend unless the property is supplied. On Windows use `gradlew.bat`.

Unit tests cover ownership, units, revision conflicts, signatures, archive corruption/password failures and replay. Android tests exercise Keystore/Room restart persistence, shared worker/UI repositories, erasure, external sign-out, and trainer/client cloud/archive/consent flows. Firestore tests cover unauthorized reads/writes, role escalation, immutable identities, query limits and consent revocation. Actual screen/file-picker checks remain part of release verification; compilation alone is not acceptance.

### Signed distribution builds

Set `TRAINPIT_KEYSTORE`, `TRAINPIT_STORE_PASSWORD` and `TRAINPIT_KEY_PASSWORD` to private signing material with alias `trainpit`, then run `./gradlew :app:assembleRelease`. Keep the same key for future updates and back it up securely outside Git. Without these variables, Gradle produces an unsigned release APK, not an installable distribution artifact. Never commit signing material, account credentials, client records or exported archives.

## Synchronization without a central server

No self-hosted server is required. Firebase provides the account authority and managed synchronization service; initial registration/sign-in, connection changes and new-device trust require internet access. Local reading/writing continues offline. Existing consenting peers with cached registered signing keys can also exchange password-encrypted `.trainpit` files without a live cloud connection.

Cloud synchronization uploads only this account's signed revisions and downloads only currently authorized records. Server timestamps/document IDs order paginated transport; revision ancestry determines conflicts. Per-source durable cursors advance in the same local transaction as received records. A successful file export never acknowledges cloud upload.

Cloud payloads use Firebase access controls, TLS and managed encryption, **not end-to-end encryption**. Offline devices cannot learn revocations immediately, and previously disclosed copies cannot be remotely recalled. Deletion markers hide records and propagate deletion intent; historical cloud revisions are not physically erased by the app. See [privacy limits](docs/ROLES_AND_PRIVACY.md).

The [synchronization contract](docs/SYNCHRONIZATION.md) documents actual cloud/file semantics, dependencies, limits, recovery and conflict behavior.

## Documentation

- [Data models and invariants](docs/DATA_MODEL.md)
- [Roles, ownership, and privacy](docs/ROLES_AND_PRIVACY.md)
- [Synchronization strategy](docs/SYNCHRONIZATION.md)
- [Firebase deployment and security tests](docs/FIREBASE.md)
- [Development roadmap](docs/ROADMAP.md)
- [GitHub delivery checklist](docs/ISSUES.md)
- [Contribution guidelines](CONTRIBUTING.md)

## License

[MIT](LICENSE). The Gradle wrapper retains its upstream Apache-2.0 notices; the project license does not relicense third-party tooling or client data.
