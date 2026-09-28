-- 物业 Web：平台下发临时密码后须首次改密
USE property_mgmt;

ALTER TABLE sys_user
  ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0
    COMMENT '1=平台下发临时密码，首次登录须修改'
    AFTER password_hash;
