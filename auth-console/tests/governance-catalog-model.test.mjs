import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  relatedRoles,
  capabilityMenus,
  menuCapabilities,
  collectRoles,
  menuTitle,
  orderedMenus,
} from '../src/features/catalog/model/catalogModel.ts';

const catalog = {
  menus: [
    { code: 'root', parent: null, route: null, any_of: [] },
    { code: 'items', parent: 'root', route: '/items', any_of: ['app.item.read'] },
  ],
  capabilities: [{ code: 'app.item.read' }, { code: 'app.item.write' }],
};
const role = (id, capabilities) => ({ id, role_code: id, version: 1, capabilities });
test('menu descendants and direct capability mapping remain distinct', () => {
  assert.deepEqual(menuCapabilities(catalog, 'root', false), []);
  assert.deepEqual(
    menuCapabilities(catalog, 'root', true).map((c) => c.code),
    ['app.item.read'],
  );
  assert.deepEqual(capabilityMenus(catalog, 'app.item.write'), []);
});
test('role association preserves immutable roles without fabricating grants', () => {
  const roles = [role('a', ['app.item.read']), role('b', ['app.item.write'])];
  assert.deepEqual(relatedRoles(roles, ['app.item.read']), [roles[0]]);
  assert.deepEqual(roles[1].capabilities, ['app.item.write']);
});
test('complete role association traverses cursor pages and deduplicates identity', async () => {
  const requests = [];
  const all = await collectRoles(async (cursor) => {
    requests.push(cursor);
    return cursor
      ? { roles: [role('b', []), role('a', [])], next_role_cursor: null }
      : { roles: [role('a', [])], next_role_cursor: 'next' };
  });
  assert.deepEqual(requests, [undefined, 'next']);
  assert.deepEqual(
    all.map((r) => r.id),
    ['a', 'b'],
  );
});
test('failed, cyclic and unbounded cursor reads never return partial associations', async () => {
  await assert.rejects(
    collectRoles(async (cursor) => {
      if (cursor) throw new Error('403');
      return { roles: [role('a', [])], next_role_cursor: 'next' };
    }),
  );
  await assert.rejects(
    collectRoles(async () => ({ roles: [], next_role_cursor: 'loop' })),
    /重复/,
  );
  let pages = 0;
  await assert.rejects(
    collectRoles(async () => ({ roles: [], next_role_cursor: `page-${++pages}` })),
    /上限/,
  );
  assert.equal(pages, 20);
});
test('organization switch cancellation stops further role reads', async () => {
  const controller = new AbortController();
  let reads = 0;
  await assert.rejects(
    collectRoles(async () => {
      reads++;
      controller.abort();
      return { roles: [], next_role_cursor: 'next' };
    }, controller.signal),
  );
  assert.equal(reads, 1);
});

test('Chinese menu labels and source order preserve legacy fallback without mutating input', () => {
  const c = {
    menus: [
      { code: 'z', label: '会员档案', position: 1 },
      { code: 'a', label: '会员经营', position: 0 },
      { code: 'legacy' },
    ],
  };
  assert.deepEqual(orderedMenus(c).map(menuTitle), ['会员经营', '会员档案', 'legacy']);
  assert.equal(c.menus[0].code, 'z');
});
