import { GovernanceDrawer as Drawer } from './presentation'
import { useState } from 'react'
import { Alert, Button, Descriptions, Input, Space, Modal } from 'antd'
import { previewCatalog, publishCatalog, type CatalogManifest, type CatalogPreview } from '../api/governance'
import { Failure } from './feedback'
import { useCommand } from './useCommand'

/** 应用Owner的技术清单发布：预览固定内容后再发布，编辑会立即作废旧预览。 */
export function CatalogEditor({ application, close, saved }: { application: string; close: () => void; saved: () => void }) {
  const [text, setText] = useState('')
  const [preview, setPreview] = useState<{ manifest: CatalogManifest; result: CatalogPreview }>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [inputError, setInputError] = useState<string>()
  const command = useCommand(publishCatalog)
  const inspect = async () => {
    setBusy(true); setError(undefined); setInputError(undefined); setPreview(undefined)
    try {
      const manifest = JSON.parse(text) as CatalogManifest
      if (manifest.application !== application) throw new Error('请选择当前应用的清单')
      const result = await previewCatalog(manifest)
      setPreview({ manifest, result })
    } catch (failure) { if (failure instanceof SyntaxError || (failure instanceof Error && failure.message === '请选择当前应用的清单')) setInputError('清单必须是属于当前应用的有效JSON。'); else setError(failure) }
    finally { setBusy(false) }
  }
  const publish = async () => {
    if (!preview) return
    const result = await command.send(commandId => ({ manifest: preview.manifest, commandId }))
    if (result) saved()
  }
  return <Drawer title="应用清单预览与发布" open width={760} onClose={() => {
    if (busy || command.busy || command.unknown) return
    if (text && !command.result) Modal.confirm({ title: '放弃未发布的清单编辑？', okText: '放弃编辑', cancelText: '继续编辑', onOk: close })
    else close()
  }} maskClosable={false}>
    <Alert type="info" showIcon message="仅当前登记应用Owner可以发布。清单发布不会自动创建成员授权。" description="粘贴应用按契约导出的JSON清单；新应用的拥有者和允许入口仍通过受控登记流程配置。" style={{ marginBottom: 16 }} />
    {inputError && <Alert type="error" showIcon message={inputError} />}
    {!!error && <Failure error={error} />}{!!command.error && <Failure error={command.error} />}
    <Input.TextArea aria-label="应用清单JSON" rows={12} value={text} disabled={busy || command.busy || command.unknown || !!command.result}
      onChange={event => { setText(event.target.value); setPreview(undefined); setError(undefined) }} placeholder="粘贴当前应用的正式清单 JSON" />
    <Space style={{ marginTop: 16, marginBottom: 16 }}><Button loading={busy} disabled={!text || command.busy || command.unknown || !!command.result} onClick={() => void inspect()}>预览清单差异</Button>
      <Button type="primary" disabled={!preview || !!command.result} loading={command.busy} onClick={() => void publish()}>{command.unknown ? '重试原发布命令' : '发布已预览清单'}</Button></Space>
    {preview && <Descriptions column={1} bordered items={[
      { label: '应用', children: preview.result.application }, { label: '版本', children: `${preview.result.current_version} → ${preview.result.proposed_version}` },
      { label: '新增能力', children: preview.result.added.join('、') || '无' }, { label: '保留能力', children: preview.result.retained.join('、') || '无' },
    ]} />}
    {command.result && <Alert type="success" showIcon message={`清单版本 ${command.result.proposed_version} 已发布`} description="固定角色与已有Grant未自动升级，请按实际需求另行授予或申请。" style={{ marginTop: 16 }} />}
  </Drawer>
}
