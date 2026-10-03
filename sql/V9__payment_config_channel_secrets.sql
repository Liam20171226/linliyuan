-- 线上支付：渠道开关、密钥、支付单、对账（可重复执行）
SET @db := DATABASE();

-- ========== payment_config 渠道与进件 ==========
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_enabled') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_enabled TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'alipay_enabled') = 0,
  'ALTER TABLE payment_config ADD COLUMN alipay_enabled TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_sub_mch_id') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_sub_mch_id VARCHAR(64) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'alipay_smid') = 0,
  'ALTER TABLE payment_config ADD COLUMN alipay_smid VARCHAR(64) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'onboarding_status') = 0,
  'ALTER TABLE payment_config ADD COLUMN onboarding_status VARCHAR(32) NOT NULL DEFAULT ''DRAFT''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'onboarding_remark') = 0,
  'ALTER TABLE payment_config ADD COLUMN onboarding_remark VARCHAR(512) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'onboarding_url') = 0,
  'ALTER TABLE payment_config ADD COLUMN onboarding_url VARCHAR(512) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_app_id') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_app_id VARCHAR(64) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_api_v3_key') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_api_v3_key VARCHAR(128) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_mch_serial_no') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_mch_serial_no VARCHAR(128) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'wechat_private_key_pem') = 0,
  'ALTER TABLE payment_config ADD COLUMN wechat_private_key_pem TEXT NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'alipay_private_key') = 0,
  'ALTER TABLE payment_config ADD COLUMN alipay_private_key TEXT NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'alipay_public_key') = 0,
  'ALTER TABLE payment_config ADD COLUMN alipay_public_key TEXT NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ========== payment_record 线上单号 ==========
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_record' AND COLUMN_NAME = 'out_trade_no') = 0,
  'ALTER TABLE payment_record ADD COLUMN out_trade_no VARCHAR(64) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_record' AND COLUMN_NAME = 'third_trade_no') = 0,
  'ALTER TABLE payment_record ADD COLUMN third_trade_no VARCHAR(128) NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ========== 支付单 / 日对账 ==========
CREATE TABLE IF NOT EXISTS pay_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  out_trade_no VARCHAR(64) NOT NULL,
  channel VARCHAR(16) NOT NULL,
  sub_mch_id VARCHAR(64) NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  third_trade_no VARCHAR(128) NULL,
  bill_ids VARCHAR(512) NOT NULL,
  payer_user_id BIGINT NULL,
  client_payload VARCHAR(2000) NULL,
  notify_raw VARCHAR(4000) NULL,
  paid_at DATETIME(3) NULL,
  refunded_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pay_order_out_trade_no (out_trade_no),
  KEY idx_pay_order_community (community_id),
  KEY idx_pay_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pay_reconcile_day (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  reconcile_date DATE NOT NULL,
  channel VARCHAR(16) NOT NULL,
  platform_count INT NOT NULL DEFAULT 0,
  platform_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  channel_count INT NOT NULL DEFAULT 0,
  channel_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  diff_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  detail_json VARCHAR(4000) NULL,
  created_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pay_reconcile (community_id, reconcile_date, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
