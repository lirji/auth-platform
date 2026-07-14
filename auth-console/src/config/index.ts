// 唯一读取 import.meta.env 的出口(去尾斜杠 + 空串默认)。
const trimSlash = (s: string) => (s || '').replace(/\/+$/, '')

export const config = {
  /** admin(:8201)基址。空=相对路径→dev vite proxy / prod nginx 同源反代。 */
  adminBaseUrl: trimSlash(import.meta.env.VITE_ADMIN_BASE_URL ?? ''),
  /** Casdoor OIDC authority(issuer)。 */
  casdoorAuthority: trimSlash(import.meta.env.VITE_CASDOOR_AUTHORITY ?? 'http://localhost:8000'),
  /** Casdoor 应用 client_id(M2 鉴权用)。 */
  casdoorClientId: import.meta.env.VITE_CASDOOR_CLIENT_ID ?? '',
  oidcScope: import.meta.env.VITE_OIDC_SCOPE ?? 'openid profile',
}
