import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  validateRetirementReport,
  validateRetirementReferences,
  validateRetirementReceipt,
} from '../src/governance/capabilityRetirement.ts';

const application = 'shop',
  capability = 'shop.write',
  hash = 'a'.repeat(64);
const report = () => ({
  application_id: application,
  capability,
  lifecycle_state: 'DEPRECATED',
  lifecycle_version: 1,
  manifest_version: 2,
  content_hash: hash,
  presentation_hash: hash,
  complete: true,
  counts: [{ kind: 'ROLE', blocking: 0, historical: 1 }],
  proof_state: 'PROVEN',
  proof_reason: null,
  proof_hash: hash,
  proof_valid_until: new Date(Date.now() + 60000).toISOString(),
  eligible: true,
  basis_hash: hash,
  checked_at: new Date().toISOString(),
});

test('complete current proven report and explicit UNPROVEN remain distinct', () => {
  assert.equal(validateRetirementReport(report(), application, capability).eligible, true);
  const unavailable = {
    ...report(),
    proof_state: 'UNPROVEN',
    proof_reason: 'NOT_CONFIGURED',
    proof_hash: null,
    proof_valid_until: null,
    eligible: false,
  };
  assert.equal(validateRetirementReport(unavailable, application, capability).eligible, false);
  assert.throws(() =>
    validateRetirementReport({ ...unavailable, eligible: true }, application, capability),
  );
});
test('missing metadata, expired proof and contradictory eligibility never generate retirement controls', () => {
  for (const changed of [
    { manifest_version: undefined },
    { content_hash: null },
    { proof_hash: undefined },
    { checked_at: undefined },
    { lifecycle_version: 0 },
    { proof_valid_until: new Date(0).toISOString() },
    { complete: false },
    { lifecycle_state: 'ACTIVE' },
    { counts: [{ kind: 'GRANT', blocking: 1, historical: 0 }] },
    {
      counts: [
        { kind: 'ROLE', blocking: 0, historical: 1 },
        { kind: 'ROLE', blocking: 0, historical: 1 },
      ],
    },
  ]) {
    assert.throws(() =>
      validateRetirementReport({ ...report(), ...changed }, application, capability),
    );
  }
  assert.throws(() => validateRetirementReport(report(), 'other', capability));
});
test('reference page requires explicit blocking state and bounded page and cursor', () => {
  const reference = {
    kind: 'ROLE',
    id: 'fixed-role',
    blocking: false,
    state: 'HISTORICAL',
    role_id: 'fixed-role',
    source_type: null,
    valid_to: null,
  };
  const page = { items: [reference], next_cursor: null, basis_hash: hash };
  assert.equal(validateRetirementReferences(page), page);
  for (const changed of [
    { items: [{ ...reference, blocking: undefined }] },
    { items: Array(101).fill(reference) },
    { next_cursor: undefined },
    { basis_hash: null },
  ]) {
    assert.throws(() => validateRetirementReferences({ ...page, ...changed }));
  }
});
test('retirement receipt preserves exact command, target and versions; malformed success remains unknown', () => {
  const body = {
    application_id: application,
    capability,
    expected_version: 1,
    reason: '全部引用退出',
    basis_hash: hash,
    command_id: 'fixed-command',
  };
  const result = {
    current: { application_id: application, capability, state: 'RETIRED', version: 2 },
    receipt: {
      application_id: application,
      capability,
      command_id: body.command_id,
      before_state: 'DEPRECATED',
      after_state: 'RETIRED',
      before_version: 1,
      after_version: 2,
      reason: body.reason,
    },
  };
  assert.equal(validateRetirementReceipt(result, body), result);
  for (const changed of [
    { application_id: 'other' },
    { capability: 'shop.read' },
    { command_id: 'new-command' },
    { before_state: 'ACTIVE' },
    { before_version: 2 },
    { reason: '替换原因' },
  ]) {
    assert.throws(() =>
      validateRetirementReceipt({ ...result, receipt: { ...result.receipt, ...changed } }, body),
    );
  }
  assert.throws(() =>
    validateRetirementReceipt(
      { ...result, current: { ...result.current, version: undefined } },
      body,
    ),
  );
});
