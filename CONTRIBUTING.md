# Contributing

## Current scope

TrainPit implements native Android trainer/client workflows, encrypted offline storage, Firebase synchronization and encrypted history exchange. Keep changes aligned with the requested fitness-management scope. Do not add unrelated tracking domains, telemetry, paid services or speculative frameworks. Consult [the delivery checklist](docs/ISSUES.md), [roadmap](docs/ROADMAP.md) and [architecture](README.md#architecture).

## Workflow

1. Follow [README setup instructions](README.md#development-setup).
2. Work from a focused issue with observable acceptance criteria and explicit non-goals.
3. Keep changes within the existing module boundaries; preserve original record ownership and signatures.
4. Update affected documentation and regression coverage for plausible consumer-visible failures.
5. Open a pull request describing actual verification and limitations. Never present an unrun check as passing.

## Architecture

- `app`: Compose screens, account controller, credential gate, background worker and composition root.
- `core:model`: platform-independent serializable records, invariants and repository contracts.
- `core:data`: encrypted Room repositories, Keystore keys, archive codecs and signed-revision validation; depends only on model.
- `core:sync`: Firebase account/relationship/key/delta adapter; depends only on model.

Data and sync remain independent. No live SQLite copying, wall-clock conflict winner, destructive migration fallback or archive-key trust-on-first-import. Account stores are shared/ref-counted in-process; do not reintroduce separate stale UI/worker caches. Preserve single-flight synchronization and atomic data/outbox/cursor boundaries.

## Verification and privacy

Use synthetic identities in Firebase emulators. Run model/data unit tests, build/lint and relevant Android integration scenarios as documented in README. Exercise the actual changed screens, including offline restarts, denied access, file pickers, credential relocking and import conflicts when affected. UI-copy/source-text checks are not functional verification.

Changing `Entry` serialization can invalidate historical signatures. Preserve schema-1 signing bytes or introduce an explicit reviewed version/migration. Room schema changes require migrations; never clear production data to make a migration pass.

Firebase rules/index changes require emulator authorization tests before deployment. Keep billing disabled; do not introduce Functions/Storage or paid services to bypass a quota. Releases must use production endpoints, a backed-up stable private signing key, APK signature verification and download/checksum verification.

Never commit real client records, credentials, private keys, keystores, CLI tokens, exported health files, identifiable screenshots, dependency directories or tool logs. The Android Firebase SDK configuration is public client metadata, not an admin secret. Report security concerns privately to maintainers rather than publishing sensitive records or exploit details in a public issue.
