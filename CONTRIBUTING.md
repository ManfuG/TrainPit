# Contributing

## Current scope

This repository contains scaffolding and planning only. Do not add features, UI, storage, synchronization, or speculative dependencies as part of initialization. Future work starts from a scoped issue in [the issue backlog](docs/ISSUES.md) and follows [the roadmap](docs/ROADMAP.md).

## Workflow

1. Follow [README setup instructions](README.md).
2. Choose an issue and agree on its acceptance criteria before implementation.
3. Create a focused branch and keep changes limited to that issue.
4. Update affected documentation and add meaningful behavioral tests when behavior is introduced.
5. Open a pull request explaining the change, verification performed, and any remaining limitations. Never describe unrun checks as passing.

## Architecture

- `app`: Android application and composition root; depends on `core:model`, `core:data`, and `core:sync`.
- `core:model`: plain Kotlin JVM domain models and contracts; no Android dependency.
- `core:data`: Android library for future local persistence; depends only on `core:model`.
- `core:sync`: Android library for future exchange and reconciliation; depends only on `core:model`.

Keep data and sync independent; compose their implementations in `app`. Use these four modules until a concrete feature warrants extraction. Follow the stack and versions in README and Gradle configuration; add libraries only when the agreed work requires them.

## Verification and privacy

When behavior exists, verify observable outcomes, not only compilation: offline use, role and consent boundaries, plan revision identity, import idempotency, conflicts, and recovery failures where relevant. Use synthetic fixtures; report commands and results precisely. Documentation-only changes need review for consistency and working references, not invented runtime test results.

Never include private client data, credentials, encryption keys, identifiable screenshots, or real exported bundles in issues, pull requests, logs, or fixtures. Use redacted descriptions and synthetic examples. Report security concerns privately to repository maintainers rather than publishing exploit details or sensitive data in a public issue.
