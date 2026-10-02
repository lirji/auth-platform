import { useEffect, useState } from 'react'
import { Alert, Button } from 'antd'
import { publishedCatalog, type Partition, type PublishedCatalog } from '../api/governance'
import { Failure } from './feedback'

/** 新命令先验证用户确认的目录；未知命令由调用方直接重放原载荷。 */
export function useCatalogSelection(initial: PublishedCatalog, partition: Partition, frozen: boolean) {
  const [catalog, setCatalog] = useState(initial)
  const [accepted, setAccepted] = useState(initial.view_hash)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<unknown>()
  useEffect(() => { if (!frozen) setCatalog(initial) }, [initial, frozen])
  const changed = catalog.view_hash !== accepted
  const verify = async () => {
    setChecking(true); setError(undefined)
    try {
      const current = await publishedCatalog(partition)
      setCatalog(current)
      if (current.view_hash !== accepted) return false
      return true
    } catch (failure) { setError(failure); return false }
    finally { setChecking(false) }
  }
  const notice = <>
    {changed && <Alert type="warning" showIcon message="目录或当前管理资格已更新，请重新核对选择" description={<Button disabled={frozen || checking} onClick={() => { setAccepted(catalog.view_hash); setError(undefined) }}>使用当前目录重新核对</Button>} />}
    {!!error && <Failure error={error} retry={() => void verify()} />}
  </>
  return { catalog, changed, checking, verify, notice }
}
