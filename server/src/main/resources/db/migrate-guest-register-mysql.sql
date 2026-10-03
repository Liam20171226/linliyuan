-- MySQL：App 游客注册字段（生产库执行一次）
ALTER TABLE sys_user ADD COLUMN password_plain VARCHAR(10) NULL COMMENT '游客明文密码(最长10)' AFTER password_hash;
ALTER TABLE sys_user ADD COLUMN register_source VARCHAR(32) NULL COMMENT 'APP_GUEST=自行注册游客' AFTER password_plain;
