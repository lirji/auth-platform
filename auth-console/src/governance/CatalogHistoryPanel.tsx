import { useEffect, useRef, useState } from 'react'
import { Button, Descriptions } from 'antd'
import { catalogHistory, catalogReleaseDetail, type CatalogHistory, type CatalogReleaseDetail } from '../api/governance'
import { CatalogChanges } from './CatalogChanges'
import { Failure } from './feedback'
import { validateCatalogHistory, validateCatalogReleaseDetail } from './catalogHistory'

const decisionLabels = { KEEP_CURRENT_GRANTS: '保持现有授权', SEPARATE_AUTHORIZATION_REVIEW: '单独复核授权' }

/** 固定历史按版本分页；候选编辑不把当前目录当作历史版本或补造旧来源。 */
export function CatalogHistoryPanel({ application }: { application: string }) {
  const [history, setHistory] = useState<CatalogHistory>()
  const [detail, setDetail] = useState<CatalogReleaseDetail>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController>()
  useEffect(() => () => pending.current?.abort(), [])
  const load = async (before?: number) => {
    pending.current?.abort(); const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined); setDetail(undefined); setHistory(undefined)
    try {
      const result = validateCatalogHistory(await catalogHistory(application, before, controller.signal), application, before)
      if (!controller.signal.aborted) setHistory(result)
    } catch (failure) { if (!controller.signal.aborted) setError(failure) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  const inspect = async (version: number) => {
    pending.current?.abort(); const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined); setDetail(undefined)
    try {
      const result = validateCatalogReleaseDetail(await catalogReleaseDetail(application, version, controller.signal), application, version)
      if (!controller.signal.aborted) setDetail(result)
    } catch (failure) { if (!controller.signal.aborted) { setError(failure); setHistory(undefined) } }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <section className="g-catalog-impact" aria-label="应用发布历史"><h3>发布历史与来源</h3>
    <p>源码声明与运行部署分别核验；旧发布未记录的来源保留为未知。当前Owner可读取本应用历史，不因此取得人员诊断资格。</p>
    <Button loading={busy} onClick={() => void load()}>刷新发布历史</Button>
    {!!error && <Failure error={error} />}
    {history && <><p>当前页 {history.items.length} 条版本记录，每页最多100条。</p>
      {!history.items.length && <p>当前应用尚无发布版本。</p>}
      {history.items.map(release => <div className="g-catalog-change g-catalog-history-version" key={release.version}><strong>版本 {release.version}</strong><p>{release.published_at}</p>
        <p>{release.source ? `源码提交 ${release.source.commit}` : '来源未记录'}</p>
        <Button disabled={busy} onClick={() => void inspect(release.version)}>查看版本 {release.version} 详情</Button></div>)}
      <Button disabled={busy || history.next_before_version === null} onClick={() => void load(history.next_before_version ?? undefined)}>读取更早的发布版本</Button></>}
    {detail && <div aria-label="固定发布详情"><h4>固定版本 {detail.release.version}</h4><Descriptions column={1} bordered size="small" items={[
      { label: '真实发布主体', children: detail.release.published_by }, { label: '发布时间', children: detail.release.published_at },
      { label: '内容摘要', children: detail.release.content_hash }, { label: '展示摘要', children: detail.release.presentation_hash || '旧版本未记录展示' },
      { label: '源码提交', children: detail.release.source?.commit || '来源未记录' }, { label: '声明制品摘要', children: detail.release.source?.artifact_hash || '来源未记录' },
      { label: '原命令', children: detail.release.command_id || '旧版本未记录命令回执' }, { label: '基础版本', children: detail.release.base_version ?? '旧版本未记录' },
      { label: '业务原因', children: detail.release.reason || '未记录' }, { label: '授权处理决定', children: detail.release.decision ? decisionLabels[detail.release.decision] : '未记录' },
    ]} />
      {detail.release.preview && <><h4>原发布预览 · 以当时基础版本为准</h4><CatalogChanges result={detail.release.preview} /></>}
      <details className="g-catalog-change"><summary>固定版本实际菜单清单</summary><pre>{JSON.stringify(detail.manifest, null, 2)}</pre></details>
    </div>}
  </section>
}
