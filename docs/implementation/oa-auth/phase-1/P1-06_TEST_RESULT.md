# P1-06 TEST_RESULT

Gate PASS. Source fingerprint: `0d61fdd0d711550c013ab879bbf2b9b1c599c3377c280a33a64585d1617e15bc`; changed path hashes: P1-06-evidence-index.json.

170 unit tests, 20 PostgreSQL tests, 30 real HTTP checks passed with zero failures/errors/skips. Controlled CLI suspension and replay were followed by rejection of the still-valid JWT. Local suspension preserves another tenant membership; global suspension denies both tenants without rewriting their memberships. Concurrent distinct commands consume one version; identical commands create one audit; injected audit failure rolls back status/version/receipt. Database constraints reject incomplete lifecycle audits.

Saved P1-03 JAR successfully read the V3 schema and still excluded the suspended member. This is bounded compatibility evidence, not a production rollback drill. No production/shared IdP changes. OA source delivery, rejoin and invitations are separate slices. No UI change.

Hygiene: IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS (no formatter), standalone static analysis N/A. Transaction advisories reviewed against the same datasource/transaction, no remote calls, bounded locks and real rollback/concurrency tests. CLI broad catch sanitizes failures and returns nonzero. Full mapping/logs: P1-06-test-results.json. Exact remote SHA CI pending.

Delivery: exact SHA `9fd58ca7a48e66438cee0da2583adc90295c179c`, [CI 36389719173](https://github.com/lirji/auth-platform/actions/runs/36389719173) SUCCESS including real IdP and controlled suspension HTTP verification. Fast-forward merged and pushed to main.
