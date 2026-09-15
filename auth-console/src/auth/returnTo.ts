/** 门户与登录页回跳只接受本站单斜杠路径，拒绝开放重定向。 */
export function sanitizeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return '/'
  }
  if (value.includes('\0') || /[\u0000-\u001F]/.test(value)) {
    return '/'
  }
  if (value === '/login' || value.startsWith('/login?') || value === '/callback' || value.startsWith('/callback?')) {
    return '/'
  }
  return value
}
