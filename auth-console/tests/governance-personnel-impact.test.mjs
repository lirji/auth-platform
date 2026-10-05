import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validatePersonnelReport } from '../src/features/personnel/model/personnelImpact.ts';

const member = 'd2703a86-f347-4b66-b9e8-2722f86a4b42',
  hash = 'a'.repeat(64),
  now = new Date().toISOString();
const facts = () => ({
  status: 'ACTIVE',
  assignments: [
    {
      id: '1',
      org_id: '1',
      type: 'PRIMARY',
      leader: false,
      valid_from: '2020-01-01',
      valid_to: null,
    },
  ],
  parent_id: null,
});
const grant = () => ({
  grant_id: 'grant',
  role_id: 'role',
  role_code: 'reader',
  role_version: 1,
  member_id: member,
  generation: 1,
  group_id: null,
  capabilities: ['shop.read'],
  scope: 'SCOPED',
  scope_rule: {
    version: 1,
    resource_type: 'store',
    clauses: [{ kind: 'SPECIFIED_STORES', values: ['S1'], include_root: false }],
  },
  source_type: 'DIRECT',
  source_id: 'source',
  valid_from: now,
  valid_to: now,
  grant_state: 'REVOKED',
  effective_state: 'REVOKED',
  grant_version: 2,
  operation_id: 'op',
  policy_state: 'READY',
  directory_state: 'READY',
});
const report = () => ({
  target: {
    membership_id: member,
    status: 'ACTIVE',
    generation: 2,
    version: 3,
    principal_status: 'ACTIVE',
    principal_version: 1,
    valid_from: now,
    valid_to: null,
  },
  directories: [
    {
      source_id: 'oa',
      aggregate_id: '1',
      aggregate_version: 3,
      payload_hash: hash,
      facts: facts(),
      history_missing: true,
    },
  ],
  directory_sources: [
    {
      source_id: 'oa',
      last_sequence: 5,
      quarantined: false,
      conflict_reasons: [],
      source_sync: 'UNKNOWN',
    },
  ],
  changes: [
    {
      source_id: 'oa',
      event_id: 'event',
      partition_sequence: 5,
      aggregate_type: 'EMPLOYEE',
      aggregate_id: '1',
      aggregate_version: 3,
      event_fingerprint: hash,
      outcome: 'APPLIED',
      before: null,
      after: { facts: facts(), member: { status: 'ACTIVE', generation: 2, version: 3 } },
      occurred_at: now,
    },
  ],
  sources: [
    {
      grant: grant(),
      current_generation: false,
      group_relations: [],
      revocation_receipt: {
        grant_id: 'grant',
        version: 2,
        status: 'COMPLETED',
        operation_id: 'op',
        desired_epoch: 3,
        applied_epoch: 3,
      },
    },
  ],
  next_change_cursor: null,
  next_source_cursor: null,
  basis_hash: hash,
  complete: true,
  checked_at: now,
});

test('unknown source freshness, missing prior history and old personal generation stay explicit', () => {
  const r = report();
  assert.equal(validatePersonnelReport(r, member), r);
  assert.equal(r.sources[0].current_generation, false);
  assert.equal(r.directory_sources[0].source_sync, 'UNKNOWN');
  assert.equal(r.changes[0].before, null);
  assert.equal(r.directories[0].history_missing, true);
});
test('missing metadata and foreign targets cannot become an empty or healthy report', () => {
  for (const changed of [
    { complete: undefined },
    { basis_hash: null },
    { checked_at: null },
    { next_change_cursor: undefined },
    { target: { ...report().target, membership_id: 'other' } },
    { target: { ...report().target, generation: 0 } },
    { directory_sources: [{ ...report().directory_sources[0], source_sync: 'ACKNOWLEDGED' }] },
    { directories: [{ ...report().directories[0], history_missing: undefined }] },
  ]) {
    assert.throws(() => validatePersonnelReport({ ...report(), ...changed }, member));
  }
});
test('group periods must contain complete assignment facts; historical relation does not become current', () => {
  const r = report();
  r.sources[0] = {
    grant: { ...grant(), source_type: 'GROUP', member_id: null, generation: 0, group_id: 'group' },
    current_generation: false,
    group_relations: [
      {
        id: 'relation',
        generation: 1,
        current: false,
        group_active: true,
        periods_json: JSON.stringify(facts().assignments),
      },
    ],
    revocation_receipt: null,
  };
  assert.equal(validatePersonnelReport(r, member), r);
  const missing = structuredClone(r);
  missing.sources[0].grant.generation = null;
  assert.throws(() => validatePersonnelReport(missing, member));
  for (const value of ['{}', '[{"id":"1"}]', '{broken']) {
    const changed = structuredClone(r);
    changed.sources[0].group_relations[0].periods_json = value;
    assert.throws(() => validatePersonnelReport(changed, member));
  }
});
test('malformed before-after evidence and unbounded pages fail instead of fabricating facts', () => {
  for (const changed of [
    { changes: [{ ...report().changes[0], after: null }] },
    { changes: [{ ...report().changes[0], outcome: 'UNKNOWN' }] },
    {
      changes: [
        {
          ...report().changes[0],
          before: { facts: facts(), member: { status: 'ACTIVE', generation: 0, version: 1 } },
        },
      ],
    },
    { sources: Array(101).fill(report().sources[0]) },
    { next_source_cursor: 'x'.repeat(257) },
  ])
    assert.throws(() => validatePersonnelReport({ ...report(), ...changed }, member));
});
test('completed revocation requires the exact source, version and actual operation', () => {
  for (const changed of [
    { grant_id: 'other' },
    { version: 1 },
    { operation_id: null },
    { status: 'REVOKED' },
  ]) {
    const r = report();
    r.sources[0].revocation_receipt = { ...r.sources[0].revocation_receipt, ...changed };
    assert.throws(() => validatePersonnelReport(r, member));
  }
  const r = report();
  r.sources[0].grant.grant_state = 'ACTIVE';
  assert.throws(() => validatePersonnelReport(r, member));
});
