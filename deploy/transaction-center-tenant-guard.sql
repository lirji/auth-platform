-- 当前本地Casdoor的用户更新API可绕过Properties修改规则。
-- 只保护交易中心组织已开通的租户绑定，避免影响其他接入项目；变更租户应重新开通身份。
CREATE OR REPLACE FUNCTION public.guard_transaction_center_tenant()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.owner = 'transaction-center' THEN
    IF NEW.owner IS DISTINCT FROM OLD.owner
       OR (NEW.properties::jsonb -> 'tenant_id') IS DISTINCT FROM (OLD.properties::jsonb -> 'tenant_id') THEN
      RAISE EXCEPTION 'transaction-center tenant binding is immutable';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.guard_transaction_center_tenant() IS '交易中心专属租户绑定保护：阻止Casdoor个人资料API修改已签发身份的业务租户';
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'guard_transaction_center_tenant' AND tgrelid = 'public."user"'::regclass) THEN
    CREATE TRIGGER guard_transaction_center_tenant BEFORE UPDATE ON public."user"
      FOR EACH ROW EXECUTE FUNCTION public.guard_transaction_center_tenant();
  END IF;
END;
$$;
COMMENT ON TRIGGER guard_transaction_center_tenant ON public."user" IS '仅限制transaction-center组织既有tenant_id及owner变更，不限制其他组织';
