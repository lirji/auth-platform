import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateCatalogDrift } from '../src/features/catalog/model/catalogDrift.ts';
const application = 'commerce',
  hash = 'a'.repeat(64),
  display = 'b'.repeat(64),
  source = { commit: 'c'.repeat(40), artifact_hash: 'd'.repeat(64) };
const candidate = {
  manifest: { application, manifest_version: 2 },
  source,
  reason: null,
  decision: null,
};
const diff = {
  application,
  current_version: 2,
  proposed_version: 2,
  content_hash: hash,
  presentation_hash: display,
  menu_changes: [],
  violations: [],
  affected_capabilities: [],
};
const report = {
  application,
  observed_at: new Date().toISOString(),
  expected_version: 2,
  content_hash: hash,
  presentation_hash: display,
  declared_source: source,
  published: { application, version: 2, content_hash: hash, presentation_hash: display, source },
  state: 'MATCHED_DECLARATION',
  diff,
  deployment: { state: 'UNKNOWN', declaration: null },
  runtime_state: 'UNKNOWN',
};
test('declaration match retains unknown runtime and exact publication evidence', () => {
  assert.equal(validateCatalogDrift(report, candidate), report);
  const missing = { ...report, published: null, state: 'NOT_PUBLISHED', diff: null };
  assert.equal(validateCatalogDrift(missing, candidate), missing);
});
test('wrong target, expected version, source, hashes or missing diff is unavailable', () => {
  for (const value of [
    { ...report, application: 'other' },
    { ...report, expected_version: 1 },
    { ...report, declared_source: null },
    { ...report, content_hash: 'x' },
    { ...report, diff: null },
    { ...report, published: { ...report.published, application: 'other' } },
    { ...report, diff: { ...diff, current_version: 1 } },
    { ...report, diff: { ...diff, presentation_hash: hash } },
  ])
    assert.throws(() => validateCatalogDrift(value, candidate));
});
test('unregistered deployment and fabricated runtime proof are rejected', () => {
  for (const value of [
    { ...report, runtime_state: 'MATCHED_DECLARATION' },
    { ...report, deployment: { state: 'MATCHED_DECLARATION', declaration: null } },
    { ...report, deployment: undefined },
    { ...report, state: 'SUCCESS' },
  ])
    assert.throws(() => validateCatalogDrift(value, candidate));
  const declaration = {
    application_id: application,
    manifest_version: 2,
    content_hash: hash,
    presentation_hash: display,
    source,
    declared_at: new Date().toISOString(),
    evidence_ref: 'isolated:1',
  };
  assert.equal(
    validateCatalogDrift(
      { ...report, deployment: { state: 'MATCHED_DECLARATION', declaration } },
      candidate,
    ).runtime_state,
    'UNKNOWN',
  );
  assert.throws(() =>
    validateCatalogDrift(
      {
        ...report,
        deployment: {
          state: 'MATCHED_DECLARATION',
          declaration: { ...declaration, application_id: 'other' },
        },
      },
      candidate,
    ),
  );
});
