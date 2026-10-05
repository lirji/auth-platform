import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  contextKey,
  organizationSearch,
  safeEntry,
  contextualEntry,
  normalizedSearch,
  applicationSearch,
} from '../src/features/governance/shared/context.ts';

test('organization switch drops application, cursor and detail state', () => {
  assert.equal(organizationSearch('tenant-b').toString(), 'tenant=tenant-b');
});
test('identity, membership generation and all partition fields isolate cached results', () => {
  const p = { tenant_id: 'a', application_id: 'app', environment: 'test' };
  const key = contextKey('issuer:person', 'member', 1, p);
  for (const next of [
    contextKey('issuer:other', 'member', 1, p),
    contextKey('issuer:person', 'other', 1, p),
    contextKey('issuer:person', 'member', 2, p),
    contextKey('issuer:person', 'member', 1, { ...p, tenant_id: 'b' }),
    contextKey('issuer:person', 'member', 1, { ...p, application_id: 'other' }),
    contextKey('issuer:person', 'member', 1, { ...p, environment: 'prod' }),
  ])
    assert.notDeepEqual(key, next);
});
test('application entry blocks executable protocols, credentials and token-bearing URLs', () => {
  for (const href of [
    'javascript:alert(1)',
    '//evil.test',
    'https://u:p@example.test/x',
    'https://example.test/?token=secret',
    'https://example.test/#token=secret',
    'http://example.test/',
  ])
    assert.equal(safeEntry(href), undefined);
  assert.equal(safeEntry('https://business.test/products'), 'https://business.test/products');
  assert.equal(safeEntry('http://127.0.0.1:5180/products'), 'http://127.0.0.1:5180/products');
});

test('business handoff passes only explicit noncredential context after safe entry validation', () => {
  const url = new URL(contextualEntry('https://business.test/products', 'tenant-b', 'test'));
  assert.equal(url.searchParams.get('tenant_id'), 'tenant-b');
  assert.equal(url.searchParams.get('environment'), 'test');
  assert.equal(url.searchParams.size, 2);
  assert.equal(
    contextualEntry('https://evil.test/?access_token=secret', 'tenant-b', 'test'),
    undefined,
  );
});

test('pasted HTML aliases normalize without overriding explicit context', () => {
  const input = new URLSearchParams(
    'tenant=t&amp%3Bapplication=old&amp%3Benvironment=test&application=commerce&environment=local',
  );
  assert.equal(
    normalizedSearch(input).toString(),
    'tenant=t&application=commerce&environment=local',
  );
  assert.equal(
    normalizedSearch(
      new URLSearchParams('amp%3Bapplication=app&amp%3Benvironment=local'),
    ).toString(),
    'application=app&environment=local',
  );
  assert.ok(input.has('amp;application'));
});
test('task navigation preserves directory page but drops old detail and task cursors', () => {
  assert.equal(
    applicationSearch(
      new URLSearchParams(
        'tenant=t&after=directory&grant=secret&request=old&permission_after=old&application=old',
      ),
      'new',
      'local',
    ).toString(),
    'tenant=t&after=directory&application=new&environment=local',
  );
});
