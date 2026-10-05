import { Alert, Button } from 'antd';
import { isAxiosError, HttpStatusCode } from 'axios';

/** 明确区分认证、拒绝、冲突和依赖故障，绝不把服务错误展示为空权限。 */
export function failureMessage(error: unknown): string {
  if (isAxiosError(error) && error.response?.data?.code === 'CAPABILITY_DEPRECATED')
    return '能力已弃用，已停止新增使用。请刷新目录并选择可用能力；现有授权按原规则继续。';
  const status = isAxiosError(error) ? error.response?.status : undefined;
  if (status === HttpStatusCode.Unauthorized) return '登录已失效，请重新登录。';
  if (status === HttpStatusCode.Forbidden) return '无权访问当前范围，请检查成员关系或管理委派。';
  if (status === HttpStatusCode.Conflict)
    return '状态已变化，请刷新后核对；未确认的提交请使用原命令重试。';
  if (status === HttpStatusCode.BadRequest) return '输入不符合要求，请检查字段、期限或范围。';
  if (status === HttpStatusCode.NotFound) return '此环境尚未开启该功能，或记录已不可用。';
  return '服务暂时不可用，当前状态尚未确认，请稍后重试。';
}
export function Failure({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <Alert
      type="error"
      showIcon
      message={failureMessage(error)}
      action={retry && <Button onClick={retry}>重试查询</Button>}
    />
  );
}
