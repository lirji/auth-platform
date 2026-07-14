// .zed schema 的轻量客户端解析(正则/花括号配对,非完整语法分析)。
// 解析失败/遇未知语法时,调用方应降级为原始文本展示。

export interface ParsedRelation {
  name: string
  subjectTypes: string[]
}
export interface ParsedPermission {
  name: string
  expr: string
}
export interface ParsedDefinition {
  name: string
  relations: ParsedRelation[]
  permissions: ParsedPermission[]
}

export function parseZed(text: string): ParsedDefinition[] {
  const clean = text.replace(/\/\/[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '')
  const defs: ParsedDefinition[] = []
  const defRe = /definition\s+([A-Za-z0-9_/]+)\s*\{/g
  let m: RegExpExecArray | null
  while ((m = defRe.exec(clean)) !== null) {
    const name = m[1]
    // 花括号配对找定义体
    let depth = 1
    let i = defRe.lastIndex
    while (i < clean.length && depth > 0) {
      if (clean[i] === '{') depth++
      else if (clean[i] === '}') depth--
      i++
    }
    const body = clean.slice(defRe.lastIndex, i - 1)

    const relations: ParsedRelation[] = []
    const relRe = /relation\s+(\w+)\s*:\s*([^\n;]+)/g
    let r: RegExpExecArray | null
    while ((r = relRe.exec(body)) !== null) {
      relations.push({
        name: r[1],
        subjectTypes: r[2].split('|').map((s) => s.trim()).filter(Boolean),
      })
    }

    const permissions: ParsedPermission[] = []
    const permRe = /permission\s+(\w+)\s*=\s*([^\n;]+)/g
    let p: RegExpExecArray | null
    while ((p = permRe.exec(body)) !== null) {
      permissions.push({ name: p[1], expr: p[2].trim() })
    }

    defs.push({ name, relations, permissions })
    defRe.lastIndex = i
  }
  return defs
}
