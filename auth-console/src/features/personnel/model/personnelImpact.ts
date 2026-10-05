import type { PermissionExplanation, RevocationReceipt, ScopeRule } from '../../../api/governance';
import { GrantSourceType, GrantState, ReceiptState } from '../../governance/shared/codes.ts';

export const DirectoryAggregate = { EMPLOYEE: 'EMPLOYEE', ORG: 'ORG' } as const;
export const PersonnelOutcome = { APPLIED: 'APPLIED', OBSOLETE: 'OBSOLETE' } as const;
export const PersonnelSync = { UNKNOWN: 'UNKNOWN' } as const;
export const PersonnelStatus = { ACTIVE: 'ACTIVE', SUSPENDED: 'SUSPENDED', LEFT: 'LEFT' } as const;
export interface PersonnelAssignment {
  id: string;
  org_id: string;
  type: string;
  leader: boolean;
  valid_from: string;
  valid_to: string | null;
}
export interface PersonnelFacts {
  status: string;
  assignments: PersonnelAssignment[];
  parent_id: string | null;
}
export interface PersonnelSnapshot {
  facts: PersonnelFacts;
  member: { status: string; generation: number; version: number } | null;
}
export interface PersonnelTarget {
  membership_id: string;
  status: string;
  generation: number;
  version: number;
  principal_status: string;
  principal_version: number;
  valid_from: string;
  valid_to: string | null;
}
export interface PersonnelDirectory {
  source_id: string;
  aggregate_id: string;
  aggregate_version: number;
  payload_hash: string;
  facts: PersonnelFacts;
  history_missing: boolean;
}
export interface PersonnelUpstream {
  source_id: string;
  last_sequence: number;
  quarantined: boolean;
  conflict_reasons: string[];
  source_sync: typeof PersonnelSync.UNKNOWN;
}
export interface PersonnelChange {
  source_id: string;
  event_id: string;
  partition_sequence: number;
  aggregate_type: string;
  aggregate_id: string;
  aggregate_version: number;
  event_fingerprint: string;
  outcome: string;
  before: PersonnelSnapshot | null;
  after: PersonnelSnapshot;
  occurred_at: string;
}
export interface PersonnelGroupRelation {
  id: string;
  generation: number;
  current: boolean;
  periods_json: string;
  group_active: boolean;
}
export interface PersonnelGrantSource {
  grant: PermissionExplanation;
  current_generation: boolean;
  group_relations: PersonnelGroupRelation[];
  revocation_receipt: RevocationReceipt | null;
}
export interface PersonnelReport {
  target: PersonnelTarget;
  directories: PersonnelDirectory[];
  directory_sources: PersonnelUpstream[];
  changes: PersonnelChange[];
  next_change_cursor: string | null;
  sources: PersonnelGrantSource[];
  next_source_cursor: string | null;
  basis_hash: string;
  complete: boolean;
  checked_at: string;
}

const integer = (v: unknown, min = 1) =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= min;
const text = (v: unknown): v is string => typeof v === 'string' && !!v && v.length <= 500;
const flag = (v: unknown) => typeof v === 'boolean';
const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v));
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const cursor = (v: unknown) => v === null || (text(v) && v.length <= 256);
const memberState = (v: PersonnelSnapshot['member']) =>
  v === null ||
  (!!v &&
    ['ACTIVE', 'LEFT', 'SUSPENDED'].includes(v.status) &&
    integer(v.generation) &&
    integer(v.version));
const assignment = (a: PersonnelAssignment) =>
  !!a &&
  text(a.id) &&
  text(a.org_id) &&
  ['PRIMARY', 'CONCURRENT', 'DOTTED'].includes(a.type) &&
  flag(a.leader) &&
  time(a.valid_from) &&
  (a.valid_to === null || time(a.valid_to));
const facts = (f: PersonnelFacts) =>
  !!f &&
  ['ACTIVE', 'PROBATION', 'LEAVING', 'LEFT', 'FROZEN', 'DISSOLVED'].includes(f.status) &&
  Array.isArray(f.assignments) &&
  f.assignments.length <= 100 &&
  f.assignments.every(assignment) &&
  (f.parent_id === null || text(f.parent_id));
const snapshot = (v: PersonnelSnapshot) => !!v && facts(v.facts) && memberState(v.member);
const rule = (v: ScopeRule) =>
  !!v &&
  v.version === 1 &&
  text(v.resource_type) &&
  Array.isArray(v.clauses) &&
  v.clauses.length > 0 &&
  v.clauses.length <= 100 &&
  v.clauses.every(
    (c) =>
      !!c &&
      text(c.kind) &&
      Array.isArray(c.values) &&
      c.values.length <= 100 &&
      c.values.every(text) &&
      flag(c.include_root),
  );
const page = (v: unknown): v is unknown[] => Array.isArray(v) && v.length <= 100;
const GROUP_GENERATION = 0;
const fail = () => {
  throw new Error('响应缺少有效人员核对信息');
};

/** 缺状态、代际、完整性或未知源端同步状态不能被解释为正常人员或同步成功。 */
export function validatePersonnelReport(r: PersonnelReport, member: string): PersonnelReport {
  const t = r?.target;
  if (
    !t ||
    t.membership_id !== member ||
    !['ACTIVE', 'LEFT', 'SUSPENDED'].includes(t.status) ||
    !integer(t.generation) ||
    !integer(t.version) ||
    !['ACTIVE', 'SUSPENDED'].includes(t.principal_status) ||
    !integer(t.principal_version) ||
    !time(t.valid_from) ||
    (t.valid_to !== null && !time(t.valid_to)) ||
    !digest(r.basis_hash) ||
    !time(r.checked_at) ||
    !flag(r.complete) ||
    !cursor(r.next_change_cursor) ||
    !cursor(r.next_source_cursor) ||
    !page(r.directories) ||
    !page(r.directory_sources) ||
    !page(r.changes) ||
    !page(r.sources)
  )
    fail();
  if (
    r.directories.some(
      (d) =>
        !d ||
        !text(d.source_id) ||
        !text(d.aggregate_id) ||
        !integer(d.aggregate_version) ||
        !digest(d.payload_hash) ||
        !facts(d.facts) ||
        !flag(d.history_missing),
    ) ||
    r.directory_sources.some(
      (s) =>
        !s ||
        !text(s.source_id) ||
        !integer(s.last_sequence, 0) ||
        !flag(s.quarantined) ||
        s.source_sync !== PersonnelSync.UNKNOWN ||
        !Array.isArray(s.conflict_reasons) ||
        s.conflict_reasons.length > 64 ||
        !s.conflict_reasons.every(text),
    ) ||
    r.changes.some(
      (c) =>
        !c ||
        !text(c.source_id) ||
        !text(c.event_id) ||
        !integer(c.partition_sequence) ||
        !integer(c.aggregate_version) ||
        !text(c.aggregate_id) ||
        !digest(c.event_fingerprint) ||
        !Object.values(DirectoryAggregate).includes(
          c.aggregate_type as (typeof DirectoryAggregate)[keyof typeof DirectoryAggregate],
        ) ||
        !Object.values(PersonnelOutcome).includes(
          c.outcome as (typeof PersonnelOutcome)[keyof typeof PersonnelOutcome],
        ) ||
        !time(c.occurred_at) ||
        (c.before !== null && !snapshot(c.before)) ||
        !snapshot(c.after),
    )
  )
    fail();
  let relations = 0;
  for (const s of r.sources) {
    const g = s?.grant;
    if (
      !g ||
      !text(g.grant_id) ||
      !text(g.role_id) ||
      !text(g.role_code) ||
      !integer(g.role_version) ||
      !integer(g.grant_version) ||
      !['DIRECT', 'OA_REQUEST', 'GROUP'].includes(g.source_type) ||
      !text(g.source_id) ||
      !text(g.scope) ||
      !['PENDING', 'ACTIVE', 'REVOKED'].includes(g.grant_state) ||
      !text(g.effective_state) ||
      !text(g.policy_state) ||
      !text(g.directory_state) ||
      !time(g.valid_from) ||
      !time(g.valid_to) ||
      !Array.isArray(g.capabilities) ||
      !g.capabilities.length ||
      !g.capabilities.every(text) ||
      !(g.scope_rule === null || rule(g.scope_rule)) ||
      !flag(s.current_generation) ||
      !Array.isArray(s.group_relations)
    )
      fail();
    // 已有GROUP契约以0表示无个人代际；组成员的真实代际在group_relations中独立展示。
    if (
      g.source_type === GrantSourceType.GROUP
        ? !text(g.group_id) || g.member_id !== null || g.generation !== GROUP_GENERATION
        : g.member_id !== member || !integer(g.generation) || g.group_id !== null
    )
      fail();
    relations += s.group_relations.length;
    if (
      relations > 10000 ||
      s.group_relations.some(
        (x) =>
          !x ||
          !text(x.id) ||
          !integer(x.generation) ||
          !flag(x.current) ||
          !flag(x.group_active) ||
          !textPeriods(x.periods_json),
      )
    )
      fail();
    if (s.revocation_receipt !== null) {
      const v = s.revocation_receipt;
      if (
        !v ||
        v.grant_id !== g.grant_id ||
        v.version !== g.grant_version ||
        !['PROCESSING', 'BLOCKED', 'COMPLETED'].includes(v.status) ||
        !integer(v.desired_epoch, 0) ||
        !integer(v.applied_epoch, 0) ||
        (v.operation_id !== null && !text(v.operation_id)) ||
        (v.status === ReceiptState.COMPLETED &&
          (g.grant_state !== GrantState.REVOKED || !text(v.operation_id)))
      )
        fail();
    }
  }
  return r;
}
function textPeriods(value: string) {
  if (typeof value !== 'string' || value.length > 32768) return false;
  try {
    const a = JSON.parse(value);
    return Array.isArray(a) && a.length <= 100 && a.every(assignment);
  } catch {
    return false;
  }
}
