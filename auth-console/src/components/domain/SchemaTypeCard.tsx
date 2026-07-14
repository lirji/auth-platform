import { Card, Divider, Space, Tag, Typography } from 'antd'
import type { ParsedDefinition } from '../../domain/zedParser'
import { OBJECT_TYPES, objectLabel } from '../../domain/lexicon'
import { PermissionTag, RelationTag } from './SemanticTag'

/** 单类型卡片:关系区 + 权限区。只接 ParsedDefinition。 */
export function SchemaTypeCard({ def }: { def: ParsedDefinition }) {
  const color = OBJECT_TYPES.find((o) => o.value === def.name)?.color ?? 'default'
  return (
    <Card
      size="small"
      styles={{ body: { minHeight: 120 } }}
      title={
        <Space>
          <Tag color={color}>{def.name}</Tag>
          <span>{objectLabel(def.name)}</span>
        </Space>
      }
    >
      {def.relations.length > 0 && (
        <>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            关系(可授予)
          </Typography.Text>
          <div style={{ marginTop: 6 }}>
            {def.relations.map((r) => (
              <div key={r.name} style={{ marginBottom: 4 }}>
                <RelationTag name={r.name} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  ← {r.subjectTypes.join(' | ')}
                </Typography.Text>
              </div>
            ))}
          </div>
        </>
      )}
      {def.relations.length > 0 && def.permissions.length > 0 && <Divider style={{ margin: '10px 0' }} />}
      {def.permissions.length > 0 && (
        <>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            权限(可判定)
          </Typography.Text>
          <div style={{ marginTop: 6 }}>
            {def.permissions.map((p) => (
              <div key={p.name} style={{ marginBottom: 4 }}>
                <PermissionTag name={p.name} />
                <code className="mono" style={{ fontSize: 12 }}>
                  = {p.expr}
                </code>
              </div>
            ))}
          </div>
        </>
      )}
    </Card>
  )
}
