# TrainPit agent context

- Product and GitHub repository: TrainPit, `ManfuG/TrainPit`.
- This initialization contains configuration and documentation only. No application features, UI, domain source, database logic, or synchronization have been implemented. Do not mistake a buildable APK without an activity for a working application.
- Keep repository text and code in English. Prefer minimal native Android solutions, explicit dependencies, and measured performance work.
- Build: Gradle wrapper 9.6.0, AGP 9.5.0 with built-in Kotlin 2.2.10; matching Kotlin JVM plugin for `core:model`. SDK 36, minSdk 26, JVM target 17. Versions live in `gradle/libs.versions.toml` and wrapper properties.
- Modules: `app` composes `core:model`, `core:data`, and `core:sync`. Data and sync depend only on model; model has no Android dependency. No extra feature modules or DI framework until justified by implemented behavior.
- Planned UI/storage: Compose/Material 3, ViewModel/StateFlow, Room/KSP. These dependencies are not installed yet.
- Read `docs/DATA_MODEL.md`, `docs/ROLES_AND_PRIVACY.md`, and `docs/SYNCHRONIZATION.md` before implementing persistence or exchange. Designs are proposals, not security guarantees.
- No mandatory central server. Start with explicit encrypted archive exchange; never synchronize live database files. Preserve ownership, causal revisions, replay safety, tombstones, scoped consent, and offline revocation limits.
- Work from `docs/ROADMAP.md` and the linked GitHub issues in `docs/ISSUES.md`. Update existing documentation when behavior changes; do not duplicate plans.
- Verification command: `./gradlew assembleDebug lintDebug :core:model:check`. There are no runtime behavior tests or launcher activity yet. Future feature work needs direct behavioral smoke verification.
- Never commit private client records, exchange bundles, signing keys, local SDK paths, or credentials. Use synthetic fixtures only.
