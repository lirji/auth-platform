import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  readCatalogCandidate,
  validateCatalogTicket,
  validateCatalogReceipt,
  validateCatalogPolicy,
} from '../src/features/catalog/model/catalogGuard.ts';
const app = 'commerce',
  hash = 'a'.repeat(64),
  display = 'b'.repeat(64),
  id = '12345678-1234-1234-1234-123456789abc';
const manifest = {
  schema_version: '1',
  application: app,
  manifest_version: 2,
  capabilities: [],
  menus: [],
};
const candidate = {
  manifest,
  source: { commit: 'c'.repeat(40), artifact_hash: 'd'.repeat(64) },
  reason: '入口调整',
  decision: 'KEEP_CURRENT_GRANTS',
};
const preview = {
  application: app,
  current_version: 1,
  proposed_version: 2,
  content_hash: hash,
  presentation_hash: display,
  publishable: true,
  menu_changes: [],
  violations: [],
  affected_capabilities: [],
};
const ticket = {
  preview_id: id,
  expires_at: new Date(Date.now() + 600000).toISOString(),
  base_version: 1,
  base_content_hash: hash,
  base_presentation_hash: display,
  candidate,
  preview,
  impact: null,
};
const receipt = {
  application: app,
  version: 2,
  content_hash: hash,
  presentation_hash: display,
  command_id: id,
  base_version: 1,
  base_content_hash: hash,
  base_presentation_hash: display,
  source: candidate.source,
  reason: candidate.reason,
  decision: candidate.decision,
  preview,
};
test('fixed export envelope and raw manifest retain known versus unknown provenance', () => {
  assert.deepEqual(
    readCatalogCandidate(
      JSON.stringify({ ...candidate, auto_grants: false, auto_roles: false }),
      app,
    ),
    candidate,
  );
  assert.equal(readCatalogCandidate(JSON.stringify(manifest), app).source, null);
  for (const v of [
    { ...candidate, auto_grants: true },
    { ...candidate, auto_roles: true },
    { ...candidate, subject: 'victim' },
    { ...candidate, source: { commit: 'x' } },
    { ...candidate, decision: 0 },
    { ...candidate, manifest: { ...manifest, application: 'other' } },
  ])
    assert.throws(() => readCatalogCandidate(JSON.stringify(v), app));
});
test('fixed ticket requires exact target, source, both hashes, impact, current base and unexpired response', () => {
  assert.equal(validateCatalogTicket(ticket, candidate, preview, null), ticket);
  for (const v of [
    { ...ticket, preview_id: 'wrong' },
    { ...ticket, expires_at: new Date(0).toISOString() },
    { ...ticket, base_version: 2 },
    { ...ticket, base_presentation_hash: null },
    { ...ticket, candidate: { ...candidate, source: null } },
    { ...ticket, candidate: { ...candidate, reason: 'other' } },
    { ...ticket, preview: { ...preview, presentation_hash: hash } },
    { ...ticket, impact: { tenant_id: 'other' } },
  ])
    assert.throws(() => validateCatalogTicket(v, candidate, preview, null));
});
test('receipt cannot substitute a current version or new command for frozen publication', () => {
  assert.equal(validateCatalogReceipt(receipt, ticket, id), receipt);
  for (const v of [
    { ...receipt, version: 3 },
    { ...receipt, command_id: 'other' },
    { ...receipt, source: null },
    { ...receipt, base_presentation_hash: hash },
    { ...receipt, preview: null },
  ])
    assert.throws(() => validateCatalogReceipt(v, ticket, id));
});
test('missing policy fields cannot masquerade as default legacy or a successful guarded enable', () => {
  const legacy = {
    application_id: app,
    mode: 'LEGACY',
    version: 0,
    enabled_by: null,
    enabled_at: null,
    reason: null,
    command_id: null,
  };
  assert.equal(validateCatalogPolicy(legacy, app), legacy);
  const guarded = {
    ...legacy,
    mode: 'GUARDED',
    version: 1,
    enabled_by: id,
    enabled_at: new Date().toISOString(),
    reason: '退出旧节点',
    command_id: id,
  };
  assert.equal(validateCatalogPolicy(guarded, app), guarded);
  for (const v of [
    { ...legacy, application_id: 'other' },
    { ...legacy, version: 1 },
    { application_id: app, mode: 'LEGACY', version: 0 },
    { ...guarded, reason: null },
    { ...guarded, mode: 'UNKNOWN' },
  ])
    assert.throws(() => validateCatalogPolicy(v, app));
});
