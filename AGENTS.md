<!-- docs-cockpit:begin · managed by docs-cockpit-build Phase 0 · do not edit inside this block -->
## docs-cockpit

This project's documentation association (module ↔ subtask ↔ spec/plan/RFC anchors)
is managed by the docs-cockpit skill family. The `use-docs-cockpit` entry skill is
the router — consult it before any doc-association work. Routing summary:

- Build the association system (0→1, whole-project planning, fill anchor gaps)
  → `docs-cockpit-build` skill
- Refresh an existing association that drifted (post-refactor, stale anchors,
  spec evolved) → `docs-cockpit-rebuild` skill
- Just re-render the dashboard HTML, no association change
  → CLI `docs-cockpit render`

Field formats and frontmatter schema: `references/schema.md` in the docs-cockpit plugin.
<!-- docs-cockpit:end -->
