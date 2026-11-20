# Data Collector — Current Roadmap

**Roadmap reset:** 20 November 2026.

- **[Rebaselined project roadmap — U01–U09](docs/ROADMAP_2026_REBASELINE.md)**: goals, dependencies, acceptance gates and next work.
- **[Cross-repository PR crosswalk](docs/MODERNIZATION_PR_CROSSWALK.md)**: original legacy implementation labels mapped to current modernization milestones; all 44 unmerged Draft PRs audited.
- **[U01 integration qualification checklist](docs/U01_INTEGRATION_QUALIFICATION.md)**: exact tasks and no-merge constraints for reconciling `ubos-local` and ODK Collect 2026.
- **[U01-T02 Android source-parity evidence](docs/U01_T02_ANDROID_SOURCE_PARITY_AUDIT.md)**: verified old administration-unit refresh code-path loss at the ODK 2026 forward-port and generic client foundations.
- **[U01-T03 baseline CI and PR integration findings](docs/U01_T03_CI_BASELINE_AND_PR_INTEGRATION.md)**: main-based CI failures, duplicate PR #1 payload and validated PR #2 fixture changes.
- **[Original M0–M21 platform tracker](odk-platform-task-progress.md)**: authoritative historical 279-task milestone IDs; 15 August 2026 snapshot, not a current production release sign-off.

**Numbering:** Preserve M0–M21 for the original platform. Use **U01–U09** for ODK 2026/longitudinal modernization and **Uxx-Tnn** for individual tasks. Never use recursive milestone labels such as `M3D4C2B2B2C3` in future work.

**Current execution focus:** **U01** branch inventory, `ubos-local` customization reconciliation, PR-stack integration planning and exact-head CI. No unapproved merges or production rollout.
