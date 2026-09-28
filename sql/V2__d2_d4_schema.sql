-- D.2～D.4 库表（对齐 03 §10～12 + payment_config）
USE property_mgmt;

-- ========== D.2 收费 / 账单 ==========

CREATE TABLE IF NOT EXISTS fee_item (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  name            VARCHAR(64) NOT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  calc_type       VARCHAR(32) NOT NULL,
  monthly_amount  DECIMAL(12,2) NULL,
  status          TINYINT NOT NULL DEFAULT 1,
  remark          VARCHAR(255) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_fee_item_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fee_item_price_rule (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  fee_item_id     BIGINT NOT NULL,
  match_key       VARCHAR(64) NOT NULL,
  unit_price      DECIMAL(12,4) NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fee_price (fee_item_id, match_key),
  KEY idx_fee_price_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bill_meter_upload (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  bill_month      CHAR(7) NOT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  meter_start     DECIMAL(14,4) NULL,
  meter_end       DECIMAL(14,4) NULL,
  unit_price      DECIMAL(12,4) NULL,
  amount          DECIMAL(12,2) NOT NULL,
  batch_id        BIGINT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_meter_room_month (community_id, room_id, bill_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bill (
  id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id            BIGINT NOT NULL,
  room_id                 BIGINT NOT NULL,
  bill_month              CHAR(7) NOT NULL,
  status                  VARCHAR(16) NOT NULL,
  due_date                DATE NULL,
  total_amount            DECIMAL(12,2) NOT NULL DEFAULT 0,
  pay_channel             VARCHAR(32) NULL,
  paid_at                 DATETIME(3) NULL,
  confirmed_by            BIGINT NULL,
  wechat_transaction_id   VARCHAR(64) NULL,
  published_at            DATETIME(3) NULL,
  created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_bill_room_month (community_id, room_id, bill_month),
  KEY idx_bill_status (community_id, status),
  KEY idx_bill_wechat (wechat_transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bill_line (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  bill_id         BIGINT NOT NULL,
  fee_category    VARCHAR(32) NOT NULL,
  title           VARCHAR(128) NOT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  snapshot_json   JSON NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_bill_line_bill (bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_record (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  bill_id         BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  payer_user_id   BIGINT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  pay_channel     VARCHAR(32) NOT NULL,
  confirmed_by    BIGINT NULL,
  paid_at         DATETIME(3) NOT NULL,
  remark          VARCHAR(512) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_pay_community (community_id),
  KEY idx_pay_bill (bill_id),
  KEY idx_pay_paid_at (community_id, paid_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_config (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id        BIGINT NOT NULL,
  guide_text          VARCHAR(2000) NULL,
  qr_attachment_id    BIGINT NULL,
  updated_by          BIGINT NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_payment_config_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== D.3 财务 / 公告 / 报修 / 投诉 ==========

CREATE TABLE IF NOT EXISTS finance_entry (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  entry_type      VARCHAR(16) NOT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  occur_date      DATE NOT NULL,
  category        VARCHAR(64) NULL,
  title           VARCHAR(128) NOT NULL,
  remark          VARCHAR(512) NULL,
  status          VARCHAR(16) NOT NULL,
  created_by      BIGINT NOT NULL,
  approved_by     BIGINT NULL,
  approved_at     DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_finance_community (community_id, occur_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS public_revenue_item (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  title           VARCHAR(128) NOT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  occur_month     CHAR(7) NULL,
  remark          VARCHAR(512) NULL,
  status          VARCHAR(32) NOT NULL,
  submitted_by    BIGINT NOT NULL,
  confirmed_by    BIGINT NULL,
  confirmed_at    DATETIME(3) NULL,
  reject_reason   VARCHAR(255) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_pub_rev_community (community_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notice (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id        BIGINT NOT NULL,
  title               VARCHAR(128) NOT NULL,
  content             TEXT NOT NULL,
  notice_type         VARCHAR(32) NOT NULL,
  urgency             TINYINT NOT NULL DEFAULT 0,
  visibility          VARCHAR(32) NOT NULL,
  effective_at        DATETIME(3) NULL,
  expire_at           DATETIME(3) NULL,
  created_by          BIGINT NOT NULL,
  creator_identity    VARCHAR(16) NOT NULL,
  cover_attachment_id BIGINT NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at          DATETIME(3) NULL,
  KEY idx_notice_community (community_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_order (
  id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id            BIGINT NOT NULL,
  room_id                 BIGINT NOT NULL,
  applicant_user_id       BIGINT NOT NULL,
  category                VARCHAR(32) NOT NULL,
  location                VARCHAR(128) NULL,
  description             VARCHAR(1000) NOT NULL,
  status                  VARCHAR(32) NOT NULL,
  assignee_user_id        BIGINT NULL,
  assigned_at             DATETIME(3) NULL,
  first_reply_at          DATETIME(3) NULL,
  escalated_to_manager    TINYINT NOT NULL DEFAULT 0,
  rating                  TINYINT NULL,
  rating_comment          VARCHAR(255) NULL,
  completed_at            DATETIME(3) NULL,
  auto_completed          TINYINT NOT NULL DEFAULT 0,
  created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_repair_community (community_id, status),
  KEY idx_repair_applicant (applicant_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_reply (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  repair_order_id     BIGINT NOT NULL,
  author_user_id      BIGINT NOT NULL,
  content             VARCHAR(1000) NOT NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_repair_reply_order (repair_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS complaint (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id        BIGINT NOT NULL,
  room_id             BIGINT NULL,
  applicant_user_id   BIGINT NOT NULL,
  contact_name        VARCHAR(64) NOT NULL,
  contact_mobile      VARCHAR(20) NOT NULL,
  category            VARCHAR(32) NOT NULL,
  target_name         VARCHAR(64) NULL,
  content             VARCHAR(1000) NOT NULL,
  status              VARCHAR(32) NOT NULL,
  handler_user_id     BIGINT NULL,
  reply_content       VARCHAR(1000) NULL,
  replied_at          DATETIME(3) NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_complaint_community (community_id, status),
  KEY idx_complaint_applicant (applicant_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== D.4 投票 ==========

CREATE TABLE IF NOT EXISTS vote (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id    BIGINT NOT NULL,
  title           VARCHAR(128) NOT NULL,
  background      TEXT NOT NULL,
  start_at        DATETIME(3) NOT NULL,
  end_at          DATETIME(3) NOT NULL,
  status          VARCHAR(16) NOT NULL,
  created_by      BIGINT NOT NULL,
  creator_role    VARCHAR(16) NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_vote_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vote_option (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  vote_id         BIGINT NOT NULL,
  option_text     VARCHAR(128) NOT NULL,
  sort_no         INT NOT NULL DEFAULT 0,
  KEY idx_vote_option_vote (vote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vote_ballot (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  vote_id         BIGINT NOT NULL,
  room_id         BIGINT NOT NULL,
  voter_user_id   BIGINT NOT NULL,
  option_id       BIGINT NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_vote_room (vote_id, room_id),
  KEY idx_ballot_voter (voter_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
