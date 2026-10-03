-- 客户端是已签发Token的稳定主体：换slot不能复用旧client来绕过不可恢复的委派撤销。
ALTER TABLE auth_governance.catalog_publisher_delegation
    ADD CONSTRAINT publisher_client_non_reuse UNIQUE(issuer,client_id),
    ADD CONSTRAINT publisher_subject_non_reuse UNIQUE(issuer,subject);
COMMENT ON CONSTRAINT publisher_client_non_reuse ON auth_governance.catalog_publisher_delegation IS '每个发行方客户端仅建立一次委派，轮换必须新客户端而非重新授权旧Token';
COMMENT ON CONSTRAINT publisher_subject_non_reuse ON auth_governance.catalog_publisher_delegation IS 'Casdoor应用subject不可换slot重复使用，旧Token不能继承新委派';
