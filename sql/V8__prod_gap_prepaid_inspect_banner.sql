-- V8 · 生产缺口补齐（对齐 schema-h2.sql）
-- 在 V1～V7 之后执行。可重复执行（IF NOT EXISTS / 信息列探测）。
-- 含：预缴协议、豁免、payment_config 预缴列、公告 Banner、工单评价维度、about_us、巡检。

-- ========== payment_config 预缴开关 ==========
SET @db := DATABASE();

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'prepaid_enabled'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE payment_config ADD COLUMN prepaid_enabled TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_config' AND COLUMN_NAME = 'prepaid_guide_text'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE payment_config ADD COLUMN prepaid_guide_text VARCHAR(2000) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 房屋费项豁免 ==========
CREATE TABLE IF NOT EXISTS room_fee_exemption (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  fee_item_id     BIGINT NULL,
  effective_from  CHAR(7) NOT NULL,
  effective_to    CHAR(7) NULL,
  reason          VARCHAR(255) NULL,
  created_by      BIGINT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_fee_ex_room (community_id, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fee_exemption_ledger (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  bill_month      CHAR(7) NOT NULL,
  bill_id         BIGINT NULL,
  exemption_id    BIGINT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  fee_item_id     BIGINT NULL,
  title           VARCHAR(128) NOT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_fee_ex_ledger (community_id, bill_month, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 旧预缴钱包表（遗留，主路径为协议方案 A）==========
CREATE TABLE IF NOT EXISTS prepaid_account (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  balance         DECIMAL(12,2) NOT NULL DEFAULT 0,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_prepaid_room (community_id, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prepaid_ledger (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  room_id           BIGINT NOT NULL,
  entry_type        VARCHAR(32) NOT NULL,
  amount            DECIMAL(12,2) NOT NULL,
  balance_after     DECIMAL(12,2) NOT NULL,
  bill_id           BIGINT NULL,
  pay_channel       VARCHAR(32) NULL,
  operator_user_id  BIGINT NULL,
  remark            VARCHAR(512) NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_prepaid_ledger_room (community_id, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 预缴协议方案 A ==========
CREATE TABLE IF NOT EXISTS prepaid_plan (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  room_id           BIGINT NOT NULL,
  list_amount       DECIMAL(12,2) NOT NULL,
  cash_amount       DECIMAL(12,2) NOT NULL,
  discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
  status            VARCHAR(16) NOT NULL,
  pay_channel       VARCHAR(32) NULL,
  remark            VARCHAR(512) NULL,
  confirmed_by      BIGINT NULL,
  confirmed_at      DATETIME(3) NULL,
  created_by        BIGINT NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_prepaid_plan_room (community_id, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prepaid_plan_item (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id         BIGINT NOT NULL,
  community_id    BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  bill_month      CHAR(7) NOT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  list_amount     DECIMAL(12,2) NOT NULL,
  cash_amount     DECIMAL(12,2) NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_prepaid_plan_item_plan (plan_id),
  KEY idx_prepaid_plan_item_cover (community_id, room_id, bill_month, fee_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 公告 Banner ==========
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'notice' AND COLUMN_NAME = 'show_on_banner'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE notice ADD COLUMN show_on_banner TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'notice' AND COLUMN_NAME = 'banner_order'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE notice ADD COLUMN banner_order INT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 统一工单评价维度（V6 之后）==========
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'service_ticket' AND COLUMN_NAME = 'rating_resolved'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE service_ticket ADD COLUMN rating_resolved TINYINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'service_ticket' AND COLUMN_NAME = 'rating_response'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE service_ticket ADD COLUMN rating_response TINYINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'service_ticket' AND COLUMN_NAME = 'rating_handling'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE service_ticket ADD COLUMN rating_handling TINYINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'service_ticket' AND COLUMN_NAME = 'rating_satisfaction'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE service_ticket ADD COLUMN rating_satisfaction TINYINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 关于我们 ==========
CREATE TABLE IF NOT EXISTS about_us (
  id          BIGINT PRIMARY KEY,
  title       VARCHAR(128) NOT NULL,
  content     MEDIUMTEXT NULL,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 巡检 ==========
CREATE TABLE IF NOT EXISTS inspect_spot (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  building_id     BIGINT NOT NULL,
  unit_id         BIGINT NULL,
  floor_id        BIGINT NULL,
  name            VARCHAR(64) NOT NULL,
  categories      VARCHAR(128) NOT NULL,
  req_safety      TEXT NULL,
  req_cleaning    TEXT NULL,
  req_facility    TEXT NULL,
  req_landscape   TEXT NULL,
  qr_token        VARCHAR(40) NOT NULL,
  sort_no         INT NOT NULL DEFAULT 0,
  created_by      BIGINT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  UNIQUE KEY uk_inspect_spot_token (qr_token),
  KEY idx_inspect_spot_floor (community_id, floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspect_plan (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  title             VARCHAR(128) NOT NULL,
  start_at          DATETIME(3) NOT NULL,
  end_at            DATETIME(3) NOT NULL,
  freq_unit         VARCHAR(16) NOT NULL,
  freq_times        INT NOT NULL DEFAULT 1,
  ordered           TINYINT NOT NULL DEFAULT 0,
  requirement_note  TEXT NULL,
  executor_roles    VARCHAR(128) NOT NULL,
  status            VARCHAR(16) NOT NULL,
  created_by        BIGINT NOT NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_inspect_plan_community (community_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspect_plan_spot (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id   BIGINT NOT NULL,
  spot_id   BIGINT NOT NULL,
  sort_no   INT NOT NULL DEFAULT 0,
  KEY idx_inspect_plan_spot (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspect_job (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  plan_id           BIGINT NOT NULL,
  seq_no            INT NOT NULL DEFAULT 1,
  status            VARCHAR(16) NOT NULL,
  assignee_user_id  BIGINT NULL,
  claimed_at        DATETIME(3) NULL,
  done_at           DATETIME(3) NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_inspect_job_plan (plan_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspect_visit (
  id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id          BIGINT NOT NULL,
  job_id                BIGINT NOT NULL,
  spot_id               BIGINT NOT NULL,
  user_id               BIGINT NOT NULL,
  note                  VARCHAR(500) NULL,
  photo_attachment_id   BIGINT NULL,
  scanned_at            DATETIME(3) NOT NULL,
  created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inspect_visit_job_spot (job_id, spot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
