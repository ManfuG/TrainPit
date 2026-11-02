# TrainPit

Offline-first Android application connecting personal trainers and their clients. Client histories stay on participant-owned devices; exchanging updates must not require a permanent central server.

Repository: [ManfuG/TrainPit](https://github.com/ManfuG/TrainPit).

## Status

Repository initialization only. The modules contain build configuration and manifests, not application code. There are no screens, activities, databases, authentication, import/export, or synchronization implementations. The debug APK has no launcher entry point and is not a usable application.

## Planned features

- **Personal Trainer (Admin):** manage multiple consenting clients, assign and revise weekly workout plans, and review recorded results. Admin is relationship-scoped, not global access.
- **Client:** view assigned plans and record training sessions, repetitions, training weights, and body weight.
- **Dashboard:** workout schedules, progress charts, and statistics derived from local records.
- **Local storage:** independent databases for trainer and client identities, with authorized history replicated between devices.
- **Data portability:** export/import complete client histories or selected training sessions.
- **Privacy:** explicit pairing and access grants, client data ownership, protected local storage, and encrypted exchange.

All items above are future work; see the [roadmap](docs/ROADMAP.md) and [initial issue checklist](docs/ISSUES.md).

## Technology stack

| Area | Decision |
| --- | --- |
| Platform | Native Android; minimum API 26, compile/target API 36 |
| Language | Kotlin; AGP built-in Kotlin 2.2.10, matching Kotlin JVM plugin for `core:model` |
| Build | Android Gradle Plugin 9.5.0, Gradle wrapper 9.6.0, Kotlin DSL, version catalog, JVM target 17 |
| UI, planned | Jetpack Compose and Material 3; ViewModel, coroutines, StateFlow |
| Persistence, planned | Room with KSP; indexed SQLite queries and transactions |
| Exchange, planned | Versioned serialized bundles using kotlinx.serialization and audited cryptographic libraries |
| Background work, planned | WorkManager for deferrable work when a usable transport is available |

AGP provides [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin); no separate Android Kotlin plugin is needed. The toolchain follows the [AGP compatibility requirements](https://developer.android.com/build/releases/agp-9-5-0-release-notes). Runtime libraries and annotation processors are deliberately not installed until their implementation issues begin. No dependency-injection framework or shared multiplatform layer is needed for this Android-only project.

## Architecture

Small modular monolith. Local storage will be the read/write source of truth; synchronization will exchange authorized domain changes, never copy live SQLite files. Use unidirectional state flow and explicit constructor dependencies, assembled in `app`.

| Module | Responsibility | Allowed project dependencies |
| --- | --- | --- |
| `app` | Android composition root; future navigation, role flows, and screens | All three core modules |
| `core:model` | Future platform-independent domain models and repository contracts | None |
| `core:data` | Future local persistence, repository implementations, and archive import/export | `core:model` |
| `core:sync` | Future pairing, exchange protocol, and transport orchestration | `core:model` |

`core:sync` must not open database files or depend on `core:data`; `app` will connect both through contracts in `core:model`. Add domain use cases only for reusable business rules. Extract feature modules only when real implementation boundaries justify them; do not create empty per-screen modules.

```text
app/
  build.gradle.kts
  src/main/AndroidManifest.xml
core/
  model/build.gradle.kts
  data/                         # Android library; manifest and build file only
  sync/                         # Android library; manifest and build file only
gradle/
  libs.versions.toml
  wrapper/
.github/
  workflows/android.yml
  ISSUE_TEMPLATE/
docs/
  DATA_MODEL.md
  ROLES_AND_PRIVACY.md
  SYNCHRONIZATION.md
  ROADMAP.md
  ISSUES.md
```

Prefer indexed, bounded queries; paginated history; batched transactional imports; incremental deltas; and locally derived chart aggregates. Measure actual bottlenecks before adding caches, protocol machinery, or abstractions.

## Development setup

1. Install Android Studio with AGP 9.5 support, or Android command-line tools, and a Gradle-compatible JDK. JDK 17 is the CI baseline; JVM bytecode targets 17.
2. Clone the repository:
   ```sh
   git clone https://github.com/ManfuG/TrainPit.git
   cd TrainPit
   ```
3. Install SDK Platform 36 and Build Tools 36.0.0; accept the Android SDK licenses. Set `ANDROID_HOME` to the SDK directory, or let Android Studio create an untracked `local.properties` with `sdk.dir`.
4. Run:
   ```sh
   ./gradlew projects
   ./gradlew assembleDebug lintDebug :core:model:check
   ```
   On Windows, use `gradlew.bat`. No globally installed Gradle is needed. Initial dependency resolution requires internet access; the planned application is offline-first, not the first build.

CI validates the Gradle wrapper and builds/lints the skeleton. There are no application behavior tests yet because there is no application behavior. Do not use real client data for development.

## Synchronization without a central server

Start with explicit exchange of encrypted, recipient-bound files through Android's document picker/share flow. Both parties can work offline and exchange updates later; no account service, hosted database, or always-on relay is required. Nearby transfer can reuse the same protocol later.

Do not synchronize whole databases or resolve edits using device wall-clock timestamps. Use immutable plan revisions, client-owned result records, per-device change sequences, acknowledgements, and explicit handling of concurrent edits. Disconnected devices cannot receive immediate updates or revocations; data already disclosed cannot be remotely recalled.

The proposed [synchronization strategy](docs/SYNCHRONIZATION.md) specifies pairing, deltas, conflict handling, tombstones, recovery, and full-history versus session exchange. It is a design, not an implemented security guarantee.

## Documentation

- [Data models and invariants](docs/DATA_MODEL.md)
- [Roles, ownership, and privacy](docs/ROLES_AND_PRIVACY.md)
- [Synchronization strategy](docs/SYNCHRONIZATION.md)
- [Development roadmap](docs/ROADMAP.md)
- [Initial GitHub issues/checklist](docs/ISSUES.md)
- [Contribution guidelines](CONTRIBUTING.md)

## License

[MIT](LICENSE). The Gradle wrapper retains its upstream Apache-2.0 notices; the project license does not relicense third-party tooling.
