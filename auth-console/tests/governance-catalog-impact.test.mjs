import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateCatalogImpact } from '../src/governance/catalogImpact.ts';
const p = { tenant_id: 'tenant', application_id: 'commerce', environment: 'test' };
const preview = {
  content_hash: 'a'.repeat(64),
  presentation_hash: 'b'.repeat(64),
  current_version: 1,
};
const report = () => ({
  ...p,
  ...preview,
  observed_at: '2026-10-02T10:00:00Z',
  basis_hash: 'c'.repeat(64),
  completeness: 'COMPLETE',
  stats: {
    role_count: 120,
    active_grant_count: 10,
    pending_grant_count: 5,
    active_people_count: 2,
    pending_people_count: 2,
    people_count: 3,
    group_grant_count: 1,
    request_policy_count: 120,
  },
  fences: { disabled_capabilities: [] },
  roles: [],
  sources: [],
  policies: [],
  next_cursor: null,
});
test('full counts are accepted independently of page length and overlapping people', () =>
  assert.equal(validateCatalogImpact(report(), p, preview).stats.people_count, 3));
test('wrong partition, candidate, missing counters and incomplete response cannot claim zero', () => {
  for (const value of [
    { ...report(), environment: 'prod' },
    { ...report(), content_hash: 'd'.repeat(64) },
    { ...report(), current_version: 2 },
    { ...report(), stats: {} },
    { ...report(), completeness: '__proto__' },
    { ...report(), basis_hash: null },
    { ...report(), sources: Array(101).fill({}) },
  ])
    assert.throws(() => validateCatalogImpact(value, p, preview));
});
test('limited basis has no continuation or confirmation', () => {
  const limited = { ...report(), completeness: 'BASIS_LIMIT_EXCEEDED', basis_hash: null };
  assert.equal(validateCatalogImpact(limited, p, preview).stats.role_count, 120);
  for (const value of [
    { ...limited, basis_hash: 'c'.repeat(64) },
    { ...limited, next_cursor: { basis_hash: 'c'.repeat(64) } },
  ])
    assert.throws(() => validateCatalogImpact(value, p, preview));
});
test('continuation must bind the exact basis', () => {
  assert.throws(() =>
    validateCatalogImpact({ ...report(), next_cursor: { basis_hash: 'd'.repeat(64) } }, p, preview),
  );
});
