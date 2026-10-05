import type { PublishedCatalog, Role, AccessState } from '../api/governance';
import { menuCapabilityCodes } from './publishedCatalog.ts';

/** 关联只表示同一固定角色的能力交集，不代表成员已经获得这些权限。 */
export function relatedRoles(roles: Role[], codes: string[]): Role[] {
  const wanted = new Set(codes);
  return roles.filter((role) => role.capabilities.some((code) => wanted.has(code)));
}

export function capabilityMenus(catalog: PublishedCatalog, code: string) {
  return catalog.menus.filter((menu) => menu.any_of.includes(code));
}

export function menuCapabilities(catalog: PublishedCatalog, menu: string, descendants: boolean) {
  const codes = descendants
    ? menuCapabilityCodes(catalog, menu)
    : new Set(catalog.menus.find((item) => item.code === menu)?.any_of ?? []);
  return catalog.capabilities.filter((cap) => codes?.has(cap.code));
}

/** 角色不可变，有限遍历得到完整关联；超限、取消或循环游标时不暴露部分结果。 */
export async function collectRoles(
  read: (cursor?: string) => Promise<AccessState>,
  signal?: AbortSignal,
): Promise<Role[]> {
  const roles = new Map<string, Role>();
  const cursors = new Set<string>();
  let cursor: string | undefined;
  const maxPages = 20;
  for (let page = 0; page < maxPages; page++) {
    signal?.throwIfAborted();
    const result = await read(cursor);
    signal?.throwIfAborted();
    for (const role of result.roles) roles.set(role.id, role);
    if (!result.next_role_cursor) return [...roles.values()];
    if (cursors.has(result.next_role_cursor)) throw new Error('角色游标重复，请刷新后重试');
    cursor = result.next_role_cursor;
    cursors.add(cursor);
  }
  throw new Error('角色关联超出单次读取上限，请缩小管理分区');
}

/** 名称来自Owner发布的数据库目录；旧目录保持编码可读。 */
export const menuTitle = (menu: PublishedCatalog['menus'][number]) => menu.label ?? menu.code;
export const orderedMenus = (catalog: PublishedCatalog) =>
  [...catalog.menus].sort(
    (a, b) => (a.position ?? 100) - (b.position ?? 100) || a.code.localeCompare(b.code),
  );
