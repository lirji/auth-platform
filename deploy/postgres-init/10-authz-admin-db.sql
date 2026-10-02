-- 审计持久化专用库(与 SpiceDB 数据面隔离)。仅在 Postgres 卷首次初始化时执行;
-- 存量卷需手动: docker exec authz-postgres createdb -U authz authz_admin
CREATE DATABASE authz_admin;
-- 初始化进程使用 POSTGRES_USER；不能固定 authz，否则自定义 PG_USER 首次启动会失败。
GRANT ALL PRIVILEGES ON DATABASE authz_admin TO CURRENT_USER;
