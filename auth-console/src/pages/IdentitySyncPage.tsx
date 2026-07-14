import { Button, Card, Result, Space, Statistic } from 'antd'
import { SyncOutlined } from '@ant-design/icons'
import { PageHeader } from '../components/layout/PageHeader'
import { colors } from '../theme/colors'
import { humanizeError, useCasdoorSync } from '../hooks/useAuthz'

export default function IdentitySyncPage() {
  const sync = useCasdoorSync()

  return (
    <>
      <PageHeader title="身份同步" description="Casdoor 组成员 → SpiceDB group#member(差量增删,幂等,可反复触发)" />
      <Card style={{ maxWidth: 680 }}>
        <Button type="primary" size="large" icon={<SyncOutlined />} loading={sync.isPending} onClick={() => sync.mutate()}>
          立即同步
        </Button>
        {sync.isSuccess && (
          <Space size="large" style={{ marginTop: 24 }}>
            <Statistic title="处理组数" value={sync.data.groups} />
            <Statistic title="新增" value={sync.data.added} valueStyle={{ color: colors.success }} />
            <Statistic title="移除" value={sync.data.removed} valueStyle={{ color: colors.error }} />
          </Space>
        )}
        {sync.isError && <Result status="warning" title="同步未成功" subTitle={humanizeError(sync.error)} style={{ paddingBlock: 12 }} />}
      </Card>
    </>
  )
}
