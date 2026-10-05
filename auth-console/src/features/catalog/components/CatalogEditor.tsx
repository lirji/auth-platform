import { GovernanceModal } from '../../governance/shared/presentation';
import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Input, Modal, Select } from 'antd';
import {
  catalogReleasePreview,
  catalogReleasePublish,
  previewCatalog,
  type CatalogCandidate,
  type CatalogImpactReport,
  type CatalogPreview,
  type CatalogPublishCommand,
  type CatalogReleaseTicket,
  type Partition,
} from '../../../api/governance';
import { Failure } from '../../governance/shared/feedback';
import { useCommand } from '../../governance/shared/useCommand';
import { CatalogChanges, completeCatalogPreview } from './CatalogChanges';
import { CatalogImpactPanel } from './CatalogImpactPanel';
import { CatalogHistoryPanel } from './CatalogHistoryPanel';
import { CatalogGuardPanel } from './CatalogGuardPanel';
import { CatalogDriftPanel } from './CatalogDriftPanel';
import {
  CatalogDecision,
  readCatalogCandidate,
  validateCatalogReceipt,
  validateCatalogTicket,
} from '../model/catalogGuard';

/** 编辑差异与固定发布预览分开；未知提交结果保留原命令和服务器票据。 */
export function CatalogEditor({
  application,
  partition,
  close,
  saved,
}: {
  application: string;
  partition: Partition;
  close: () => void;
  saved: () => void;
}) {
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<{ candidate: CatalogCandidate; result: CatalogPreview }>();
  const [ticket, setTicket] = useState<CatalogReleaseTicket>();
  const [reason, setReason] = useState('');
  const [decision, setDecision] = useState<CatalogCandidate['decision']>(null);
  const [impact, setImpact] = useState<CatalogImpactReport>();
  const [confirmImpact, setConfirmImpact] = useState(false);
  const [error, setError] = useState<unknown>();
  const [inputError, setInputError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [guardLocked, setGuardLocked] = useState(false);
  const [now, setNow] = useState(Date.now());
  const pending = useRef<AbortController>();
  const ticketRef = useRef<CatalogReleaseTicket>();
  const command = useCommand(async (input: CatalogPublishCommand) => {
    if (!ticketRef.current) throw new Error('原发布票据缺失');
    return validateCatalogReceipt(
      await catalogReleasePublish(input),
      ticketRef.current,
      input.command_id,
    );
  });
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => {
      window.clearInterval(timer);
      pending.current?.abort();
    };
  }, []);
  const frozen = busy || guardLocked || command.busy || command.unknown || !!command.result;
  const publishable =
    !!preview &&
    completeCatalogPreview(preview.result) &&
    preview.result.publishable &&
    !preview.result.violations.length &&
    preview.result.proposed_version > preview.result.current_version;
  const expired = !!ticket && Date.parse(ticket.expires_at) <= now;
  const invalidate = () => {
    setTicket(undefined);
    ticketRef.current = undefined;
    setError(undefined);
    setInputError(undefined);
    command.reset();
  };
  const inspect = async () => {
    setBusy(true);
    setPreview(undefined);
    setImpact(undefined);
    setConfirmImpact(false);
    invalidate();
    try {
      let candidate: CatalogCandidate;
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
      const result = await previewCatalog(candidate.manifest);
      setPreview({ candidate, result });
      setReason(candidate.reason ?? '');
      setDecision(candidate.decision);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  const fix = async () => {
    if (!preview || !publishable || frozen) return;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    invalidate();
    const candidate = { ...preview.candidate, reason: reason.trim() || null, decision };
    if (decision && !candidate.reason) {
      setInputError('选择授权处理决定时，请填写业务变更原因');
      setBusy(false);
      return;
    }
    const basis =
      confirmImpact && impact?.basis_hash ? { ...partition, basis_hash: impact.basis_hash } : null;
    try {
      const result = validateCatalogTicket(
        await catalogReleasePreview({ ...candidate, impact: basis }, controller.signal),
        candidate,
        preview.result,
        basis,
      );
      if (!controller.signal.aborted) {
        setTicket(result);
        ticketRef.current = result;
      }
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  const publish = async () => {
    if (!ticket || (expired && !command.unknown) || guardLocked) return;
    const result = await command.send((commandId) => ({
      command_id: commandId,
      preview_id: ticket.preview_id,
    }));
    if (result) saved();
  };
  return (
    <GovernanceModal
      title="应用清单预览与发布"
      open
      width={960}
      onCancel={() => {
        if (busy || guardLocked || command.busy || command.unknown) return;
        if (text && !command.result)
          Modal.confirm({
            title: '放弃未发布的清单编辑？',
            okText: '放弃编辑',
            cancelText: '继续编辑',
            onOk: close,
          });
        else close();
      }}
      closeDisabled={busy || guardLocked || command.busy || command.unknown}
      maskClosable={false}
      footer={
        <>
          <Button loading={busy} disabled={!text || frozen} onClick={() => void inspect()}>
            预览清单差异
          </Button>
          <Button
            disabled={!publishable || frozen || (confirmImpact && !impact?.basis_hash)}
            onClick={() => void fix()}
          >
            固定发布预览
          </Button>
          <Button
            type="primary"
            disabled={
              !ticket || (expired && !command.unknown) || guardLocked || busy || !!command.result
            }
            loading={command.busy}
            onClick={() => void publish()}
          >
            {command.unknown ? '重试原发布命令' : '发布固定预览'}
          </Button>
        </>
      }
    >
      <Alert
        type="info"
        showIcon
        message="仅当前登记应用Owner可以发布。清单发布不会自动创建成员授权。"
        description="粘贴应用导出的清单或来源信封，先查看差异，再固定预览。路由或权限映射变更需要业务原因和授权处理决定。"
        style={{ marginBottom: 16 }}
      />
      {inputError && <Alert type="error" showIcon message={inputError} />}
      {!!error && <Failure error={error} />}
      {!!command.error && <Failure error={command.error} />}
      <Input.TextArea
        aria-label="应用清单JSON"
        rows={8}
        value={text}
        disabled={frozen}
        onChange={(event) => {
          setText(event.target.value);
          setPreview(undefined);
          setImpact(undefined);
          setConfirmImpact(false);
          invalidate();
        }}
        placeholder="粘贴当前应用的正式清单或导出信封 JSON"
      />
      {preview && (
        <>
          <Descriptions
            column={1}
            bordered
            style={{ marginTop: 20 }}
            items={[
              {
                label: '应用 / 版本',
                children: `${preview.result.application} · ${preview.result.current_version} → ${preview.result.proposed_version}`,
              },
              {
                label: '声明来源',
                children: preview.candidate.source
                  ? `源码 ${preview.candidate.source.commit} / 制品 ${preview.candidate.source.artifact_hash}`
                  : '来源未记录 · UNKNOWN',
              },
              { label: '新增能力', children: preview.result.added.join('、') || '无' },
              { label: '保留能力', children: preview.result.retained.join('、') || '无' },
            ]}
          />
          <CatalogChanges
            key={`${preview.result.content_hash}-${preview.result.presentation_hash}`}
            result={preview.result}
          />
          <section className="g-catalog-release-form" aria-label="发布处理决定">
            <h3>变更处理</h3>
            <label htmlFor="catalog-release-reason">业务变更原因</label>
            <Input.TextArea
              id="catalog-release-reason"
              aria-label="业务变更原因"
              rows={2}
              maxLength={500}
              value={reason}
              disabled={frozen}
              onChange={(event) => {
                setReason(event.target.value);
                invalidate();
              }}
            />
            <label htmlFor="catalog-release-decision">授权处理决定</label>
            <Select
              id="catalog-release-decision"
              aria-label="授权处理决定"
              allowClear
              value={decision ?? undefined}
              disabled={frozen}
              onChange={(value) => {
                setDecision(value ?? null);
                invalidate();
              }}
              placeholder="选择实际处理方式"
              options={[
                { value: CatalogDecision.KEEP_CURRENT_GRANTS, label: '保留当前授权' },
                { value: CatalogDecision.SEPARATE_AUTHORIZATION_REVIEW, label: '另行开展授权复核' },
              ]}
            />
            <p>该决定是发布记录，不会自动创建、扩大或撤销 Grant。仅展示调整可以不填写决定。</p>
          </section>
          <CatalogImpactPanel
            key={`${partition.tenant_id}-${partition.environment}-${preview.result.content_hash}-${preview.result.presentation_hash}`}
            partition={partition}
            manifest={preview.candidate.manifest}
            preview={preview.result}
            locked={frozen}
            onBasis={(report) => {
              setImpact(report);
              setConfirmImpact(false);
              invalidate();
            }}
          />
          <Checkbox
            checked={confirmImpact}
            disabled={frozen || !impact?.basis_hash}
            onChange={(event) => {
              setConfirmImpact(event.target.checked);
              invalidate();
            }}
          >
            将当前分区的完整影响依据纳入发布确认
          </Checkbox>
          <p>未勾选时仅发布技术目录；当前分区分析不覆盖其他企业或环境。</p>
        </>
      )}
      {ticket && (
        <section className="g-catalog-ticket" aria-label="固定发布预览">
          <Alert
            type={expired ? 'warning' : 'success'}
            showIcon
            message={expired ? '固定预览已到期' : '固定预览已保存'}
            description={
              command.unknown
                ? '发布结果未知，请用原命令重试；已成功的命令在到期后仍可读取原回执。'
                : '候选和来源已固定。发布时重新核对当前Owner、基础双摘要、期限和所选影响依据。'
            }
          />
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              { label: '预览 ID', children: ticket.preview_id },
              { label: '有效期至', children: ticket.expires_at },
              { label: '基础版本', children: ticket.base_version },
              { label: '基础目录摘要', children: ticket.base_content_hash ?? '尚未发布' },
              { label: '基础展示摘要', children: ticket.base_presentation_hash ?? '尚未发布' },
              {
                label: '影响确认',
                children: ticket.impact
                  ? `${ticket.impact.tenant_id} / ${ticket.impact.environment} / ${ticket.impact.basis_hash}`
                  : '未附加人员影响确认',
              },
            ]}
          />
        </section>
      )}
      <CatalogGuardPanel
        application={application}
        locked={busy || command.busy || command.unknown || !!command.result}
        onLockChange={setGuardLocked}
      />
      <CatalogDriftPanel
        application={application}
        text={text}
        locked={busy || guardLocked || command.busy || command.unknown}
      />
      <CatalogHistoryPanel key={application} application={application} />
      {command.result && (
        <Alert
          type="success"
          showIcon
          message={`清单版本 ${command.result.version} 已发布`}
          description="固定角色与已有Grant未自动升级，请按实际需求另行授予或申请。"
          style={{ marginTop: 16 }}
        />
      )}
    </GovernanceModal>
  );
}
