import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions } from 'antd';
import { catalogDrift, type CatalogDriftReport } from '../api/governance';
import { readCatalogCandidate } from './catalogGuard';
import { driftLabels, DriftState, validateCatalogDrift } from './catalogDrift';
import { CatalogChanges } from './CatalogChanges';
import { Failure } from './feedback';

/** 本次来源／发布／部署声明分别核对，未接受信运行核验时明确UNKNOWN。 */
export function CatalogDriftPanel({
  application,
  text,
  locked,
}: {
  application: string;
  text: string;
  locked: boolean;
}) {
  const [report, setReport] = useState<CatalogDriftReport>();
  const [error, setError] = useState<unknown>();
  const [inputError, setInputError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const pending = useRef<AbortController>();
  useEffect(() => {
    pending.current?.abort();
    setReport(undefined);
    setError(undefined);
    setInputError(undefined);
    setBusy(false);
  }, [text, application]);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    if (locked) {
      pending.current?.abort();
      setBusy(false);
      setReport(undefined);
    }
  }, [locked]);
  const inspect = async () => {
    if (locked) return;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    setReport(undefined);
    setError(undefined);
    setInputError(undefined);
    setBusy(true);
    try {
      let candidate;
      try {
        candidate = readCatalogCandidate(text, application);
      } catch (failure) {
        setInputError(
          failure instanceof SyntaxError
            ? '请输入有效 JSON'
            : failure instanceof Error
              ? failure.message
              : '清单输入无效',
        );
        return;
      }
      const result = validateCatalogDrift(
        await catalogDrift(candidate, controller.signal),
        candidate,
      );
      if (!controller.signal.aborted) setReport(result);
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  return (
    <section className="g-catalog-impact" aria-label="来源与发布漂移核对">
      <h3>来源与发布核对</h3>
      <p>
        读取当前输入的固定源码声明，与当前发布目录和受信配置中的部署声明分别比较。核对不会发布清单或修改授权。
      </p>
      <Button disabled={!text || locked} loading={busy} onClick={() => void inspect()}>
        核对当前来源与发布
      </Button>
      {inputError && <Alert type="error" showIcon message={inputError} />}
      {!!error && (
        <>
          <Failure error={error} />
          <p>未取得可用核验，不能据此判断目录一致。请核对当前Owner、目标和服务状态后重试。</p>
        </>
      )}
      {report && (
        <>
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              { label: '源码 / 发布', children: driftLabels[report.state] },
              { label: '本次核验时点', children: report.observed_at },
              {
                label: '目标 / 候选版本',
                children: `${report.application} / ${report.expected_version}`,
              },
              {
                label: '固定声明来源',
                children: report.declared_source
                  ? `${report.declared_source.commit} / ${report.declared_source.artifact_hash}`
                  : '来源未记录 · UNKNOWN',
              },
              {
                label: '声明目录 / 展示摘要',
                children: `${report.content_hash} / ${report.presentation_hash}`,
              },
              {
                label: '当前成功发布',
                children: report.published
                  ? `版本 ${report.published.version} / ${report.published.published_at} / ${report.published.published_by}`
                  : '尚未发布',
              },
              {
                label: '已发布来源',
                children: report.published?.source
                  ? `${report.published.source.commit} / ${report.published.source.artifact_hash}`
                  : '来源未记录 · UNKNOWN',
              },
              {
                label: '已发布目录 / 展示摘要',
                children: report.published
                  ? `${report.published.content_hash} / ${report.published.presentation_hash}`
                  : '尚未发布',
              },
              { label: '部署声明 / 发布', children: driftLabels[report.deployment.state] },
              {
                label: '部署声明证据',
                children: report.deployment.declaration
                  ? `${report.deployment.declaration.evidence_ref} / ${report.deployment.declaration.declared_at}`
                  : '未登记受信部署声明',
              },
              ...(report.deployment.declaration
                ? [
                    {
                      label: '部署声明版本 / 来源',
                      children: `${report.deployment.declaration.manifest_version} / ${report.deployment.declaration.source.commit} / ${report.deployment.declaration.source.artifact_hash}`,
                    },
                  ]
                : []),
              { label: '实际运行核验', children: '运行状态未知 · UNKNOWN' },
            ]}
          />
          <Alert
            type={report.state === DriftState.MATCHED_DECLARATION ? 'info' : 'warning'}
            showIcon
            message="声明比较不证明实际运行一致"
            description="当前未接入受信运行制品核验。部署声明来自服务配置，源码来源来自当前输入；二者都不作为实际运行证明。修正目录需显式发布更高版本，授权变更另行处理。"
          />
          {report.diff && (
            <details className="g-catalog-change g-catalog-drift-details">
              <summary>查看当前固定两版的具体差异</summary>
              <CatalogChanges result={report.diff} />
            </details>
          )}
        </>
      )}
    </section>
  );
}
