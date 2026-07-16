-- 审计持久化专用库(与 SpiceDB 数据面隔离)。仅在 Postgres 卷首次初始化时执行;
-- 存量卷需手动: docker exec authz-postgres createdb -U authz authz_admin
CREATE DATABASE authz_admin;
GRANT ALL PRIVILEGES ON DATABASE authz_admin TO authz;
