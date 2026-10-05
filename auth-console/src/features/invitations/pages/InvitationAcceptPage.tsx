import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Space } from 'antd';
import { Link } from 'react-router-dom';
import { acceptInvitation } from '../../../api/governance';
import { Failure } from '../../governance/shared/feedback';
import { useCommand } from '../../governance/shared/useCommand';
import { PageHeader } from '../../../components/layout/PageHeader';

/** 独立于组织目录，首次受邀且尚无Membership的登录者也能接受。证明只驻留当前表单。 */
export default function InvitationAcceptPage() {
  const [form] = Form.useForm();
  const command = useCommand(acceptInvitation);
  const [completed, setCompleted] = useState(false);
  const finish = async (values: { invitation_id: string; token: string }) => {
    const result = await command.send(() => values);
    if (result) {
      form.resetFields();
      setCompleted(true);
    }
  };
  return (
    <main className="app-content" style={{ maxWidth: 720, margin: 'auto' }}>
      <PageHeader
        title="接受组织邀请"
        description="邀请须与当前登录身份匹配。加入组织不会自动获得商品或导出权限。"
      />
      <Card>
        {!!command.error && <Failure error={command.error} />}
        {completed ? (
          <Alert
            type="success"
            showIcon
            message="已加入邀请对应组织"
            description={<Link to="/governance">前往我的工作台</Link>}
          />
        ) : (
          <>
            {command.unknown && (
              <Alert type="warning" message="接受结果尚未确认，请使用原邀请重试。" />
            )}
            <Form
              form={form}
              layout="vertical"
              onFinish={finish}
              disabled={command.busy || command.unknown}
            >
              <Form.Item
                name="invitation_id"
                label="邀请编号"
                rules={[
                  { required: true },
                  { pattern: /^[0-9a-f-]{36}$/i, message: '请输入完整邀请编号' },
                ]}
              >
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="token"
                label="邀请证明"
                rules={[
                  { required: true },
                  { pattern: /^[A-Za-z0-9_-]{43}$/, message: '邀请证明格式不正确' },
                ]}
              >
                <Input.Password autoComplete="off" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={command.busy}>
                接受邀请
              </Button>
            </Form>
            {command.unknown && (
              <Button
                type="primary"
                loading={command.busy}
                onClick={() => void finish(form.getFieldsValue())}
              >
                重试原接受
              </Button>
            )}
          </>
        )}
        <Space style={{ marginTop: 16 }}>
          <Link to="/governance">我的工作台</Link>
        </Space>
      </Card>
    </main>
  );
}
