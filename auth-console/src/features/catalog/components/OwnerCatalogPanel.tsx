import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, Card, Select, Skeleton, Space, Button } from 'antd';
import { ownerCatalog } from '../../../api/governance';
import { useGovernanceContext } from '../../governance/shell/GovernancePage';
import { Failure } from '../../governance/shared/feedback';
import { CapabilityLifecycleControl } from './CapabilityLifecycleControl';
import { CapabilityRetirementPanel } from './CapabilityRetirementPanel';

/** Owner最后一项委派退出后的目录治理，仍不请求角色或成员来源数据。 */
export function OwnerCatalogPanel() {
  const { partition, queryKey } = useGovernanceContext();
  const [selected, setSelected] = useState<string>();
  const query = useQuery({
    queryKey: [...queryKey, 'owner-catalog'],
    queryFn: () => ownerCatalog(partition.application_id),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  if (query.error) return <Failure error={query.error} retry={() => void query.refetch()} />;
  if (!query.data) return <Skeleton active />;
  const cap =
    query.data.capabilities.find((c) => c.code === selected) ?? query.data.capabilities[0];
  return (
    <Card
      title="应用 Owner 目录治理"
      extra={
        <Button loading={query.isFetching} onClick={() => void query.refetch()}>
          刷新目录
        </Button>
      }
    >
      <Alert
        type="info"
        message="当前仅有应用 Owner 目录资格"
        description="此入口展示目录、生命周期与退出条件；人员及来源明细仍需当前分区独立诊断授权。"
      />
      <Space direction="vertical" style={{ marginTop: 16, width: '100%' }}>
        <span>目录版本 {query.data.manifest_version}</span>
        <Select
          aria-label="Owner目录能力"
          style={{ width: '100%' }}
          value={cap?.code}
          onChange={setSelected}
          options={query.data.capabilities.map((c) => ({ value: c.code, label: c.code }))}
        />
      </Space>
      {cap && (
        <>
          <CapabilityLifecycleControl
            key={`lifecycle:${cap.code}`}
            application={partition.application_id}
            capability={cap}
            owner
            refreshing={query.isFetching}
            refresh={async () => {
              await query.refetch();
            }}
          />
          <CapabilityRetirementPanel
            key={`retirement:${cap.code}`}
            partition={partition}
            capability={cap.code}
            owner
            refresh={async () => {
              await query.refetch();
            }}
          />
        </>
      )}
    </Card>
  );
}
