# WattUp development

- Apply the ponytail skill to every WattUp development task. Use full mode unless the user selects another level.
- Prefer the smallest complete change. Reuse existing JDBC, Flyway, tests, and CI. Use names, types, and tests to explain behavior. Add a code comment only when it records a constraint the code cannot express.
- Check `docs/development/PROGRESS.md` and the user-provided WattUp development instructions before starting a category. Start from current `origin/main`. Keep verified commits small and use a feature branch and PR per category.
- Squash merge after CI and all required reviews pass. Preserve applied migrations and keep real credentials and external API traffic out of tests.
