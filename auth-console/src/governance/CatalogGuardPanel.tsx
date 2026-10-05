import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Input } from 'antd';
import {
  catalogEnableGuard,
  catalogGuardPolicy,
  type CatalogEnableCommand,
  type CatalogGuardPolicy,
} from '../api/governance';
import {
  CatalogGuardMode,
  validateCatalogEnableReceipt,
  validateCatalogPolicy,
} from './catalogGuard';
import { Failure } from './feedback';
import { useCommand } from './useCommand';

/** 策略按需读取；启用必须由当前Owner显式声明，不自动升级生产或解除门禁。 */
export function CatalogGuardPanel({
  application,
  locked,
  onLockChange,
}: {
  application: string;
  locked: boolean;
  onLockChange: (locked: boolean) => void;
}) {
  const [policy, setPolicy] = useState<CatalogGuardPolicy>();
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [exited, setExited] = useState(false);
  const [reason, setReason] = useState('');
  const command = useCommand(async (input: CatalogEnableCommand) =>
    validateCatalogEnableReceipt(await catalogEnableGuard(input), input),
  );
  useEffect(() => {
    onLockChange(busy || command.busy || command.unknown);
  }, [busy, command.busy, command.unknown, onLockChange]);
  const inspect = async () => {
    if (locked || command.unknown) return;
    setBusy(true);
    setError(undefined);
    setPolicy(undefined);
    try {
      setPolicy(validateCatalogPolicy(await catalogGuardPolicy(application), application));
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  const enable = async () => {
    if (!policy || policy.mode !== CatalogGuardMode.LEGACY) return;
    const result = await command.send((commandId) => ({
      application_id: application,
      command_id: commandId,
      expected_version: policy.version,
      legacy_writers_exited: exited,
      reason: reason.trim(),
    }));
    if (result) setPolicy(result);
  };
  const frozen = locked || busy || command.busy || command.unknown;
  return (
    <section className="g-catalog-guard" aria-label="应用发布模式">
      <h3>应用发布模式</h3>
      <Button disabled={locked || command.unknown} loading={busy} onClick={() => void inspect()}>
        读取发布模式
      </Button>
      {!!error && <Failure error={error} />}
      {!!command.error && <Failure error={command.error} />}
      {policy && (
        <>
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              {
                label: '模式',
                children:
                  policy.mode === CatalogGuardMode.GUARDED
                    ? '受控发布 · GUARDED'
                    : '兼容旧入口 · LEGACY',
              },
              { label: '策略版本', children: policy.version },
              ...(policy.mode === CatalogGuardMode.GUARDED
                ? [
                    {
                      label: '启用人 / 时点',
                      children: `${policy.enabled_by} / ${policy.enabled_at}`,
                    },
                    { label: '启用原因', children: policy.reason },
                  ]
                : []),
            ]}
          />
          {policy.mode === CatalogGuardMode.LEGACY && (
            <details>
              <summary>启用受控发布</summary>
              <Alert
                type="warning"
                showIcon
                message="启用后所有当前旧写入口均拒绝新发布"
                description="先升级调用方并退出所有旧写节点。启用为单向操作；回退程序不能解除门禁。本操作仅改变当前应用的发布策略。"
              />
              <Checkbox
                checked={exited}
                disabled={frozen}
                onChange={(event) => {
                  setExited(event.target.checked);
                  command.reset();
                }}
              >
                我已完成调用方升级，并退出当前应用的全部旧写节点
              </Checkbox>
              <label htmlFor="catalog-guard-reason">启用原因</label>
              <Input.TextArea
                id="catalog-guard-reason"
                aria-label="启用受控发布原因"
                value={reason}
                maxLength={500}
                rows={2}
                disabled={frozen}
                onChange={(event) => {
                  setReason(event.target.value);
                  command.reset();
                }}
              />
              <Button
                disabled={!exited || !reason.trim() || locked || busy}
                loading={command.busy}
                onClick={() => void enable()}
              >
                {command.unknown ? '重试原启用命令' : '启用当前应用受控发布'}
              </Button>
            </details>
          )}
        </>
      )}
    </section>
  );
}
