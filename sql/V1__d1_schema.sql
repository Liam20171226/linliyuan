-- D.1 库表（对齐 03）。库名示例：property_mgmt
CREATE DATABASE IF NOT EXISTS property_mgmt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE property_mgmt;

CREATE TABLE IF NOT EXISTS sys_user (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  username        VARCHAR(64)  NULL,
  mobile          VARCHAR(20)  NULL,
  real_name       VARCHAR(64)  NULL,
  id_card_no      VARCHAR(32)  NULL,
  password_hash   VARCHAR(255) NULL,
  must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '1=平台下发临时密码，首次登录须修改',
  is_platform_admin TINYINT NOT NULL DEFAULT 0,
  status          TINYINT NOT NULL DEFAULT 1,
  last_login_at   DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_mobile (mobile),
  UNIQUE KEY uk_id_card (id_card_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_wechat (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  app_id     VARCHAR(64) NOT NULL,
  openid     VARCHAR(64) NOT NULL,
  unionid    VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_app_openid (app_id, openid),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS community (
  id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
  name                  VARCHAR(128) NOT NULL,
  address               VARCHAR(255) NULL,
  intro                 TEXT NULL,
  cover_attachment_id   BIGINT NULL,
  contact_phone         VARCHAR(32) NULL,
  has_formal_committee  TINYINT NOT NULL DEFAULT 0,
  status                TINYINT NOT NULL DEFAULT 1,
  created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at            DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS building (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  name         VARCHAR(64) NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3) NULL,
  UNIQUE KEY uk_community_name (community_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS unit (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  building_id  BIGINT NOT NULL,
  name         VARCHAR(64) NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3) NULL,
  UNIQUE KEY uk_building_name (building_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS floor (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  building_id  BIGINT NOT NULL,
  unit_id      BIGINT NOT NULL,
  name         VARCHAR(64) NOT NULL,
  floor_no     INT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3) NULL,
  UNIQUE KEY uk_unit_name (unit_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS community_house_type (
  id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id             BIGINT NOT NULL,
  name                     VARCHAR(64) NOT NULL,
  property_fee_unit_price  DECIMAL(12,4) NULL,
  status                   TINYINT NOT NULL DEFAULT 1,
  sort_no                  INT NULL DEFAULT 0,
  created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at               DATETIME(3) NULL,
  UNIQUE KEY uk_community_type_name (community_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id   BIGINT NOT NULL,
  building_id    BIGINT NOT NULL,
  unit_id        BIGINT NOT NULL,
  floor_id       BIGINT NOT NULL,
  room_no        VARCHAR(32) NOT NULL,
  house_type_id  BIGINT NULL,
  area_sqm       DECIMAL(10,2) NULL,
  status         TINYINT NOT NULL DEFAULT 1,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3) NULL,
  UNIQUE KEY uk_floor_room (floor_id, room_no),
  KEY idx_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS parking_space (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  space_no     VARCHAR(64) NOT NULL,
  room_id      BIGINT NULL,
  remark       VARCHAR(255) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3) NULL,
  UNIQUE KEY uk_community_space (community_id, space_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_vehicle (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  room_id           BIGINT NOT NULL,
  plate_no          VARCHAR(32) NOT NULL,
  parking_space_id  BIGINT NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_community_plate (community_id, plate_no),
  KEY idx_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_occupant (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id   BIGINT NOT NULL,
  room_id        BIGINT NOT NULL,
  user_id        BIGINT NOT NULL,
  resident_role  VARCHAR(32) NOT NULL,
  status         VARCHAR(16) NOT NULL,
  source         VARCHAR(32) NOT NULL,
  approved_at    DATETIME(3) NULL,
  approved_by    BIGINT NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_status (room_id, status),
  KEY idx_user_status (user_id, status),
  KEY idx_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS staff_community (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  staff_role   VARCHAR(32) NOT NULL,
  status       VARCHAR(16) NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_staff_community_role (community_id, user_id, staff_role),
  KEY idx_community_user (community_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS committee_member (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  title        VARCHAR(32) NOT NULL,
  status       VARCHAR(16) NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_community_user (community_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_application (
  id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id         BIGINT NOT NULL,
  room_id              BIGINT NOT NULL,
  applicant_user_id    BIGINT NOT NULL,
  applicant_name       VARCHAR(64) NOT NULL,
  applicant_mobile     VARCHAR(20) NOT NULL,
  applicant_id_card_no VARCHAR(32) NULL,
  house_type_id        BIGINT NULL,
  apply_role           VARCHAR(32) NOT NULL,
  apply_message        VARCHAR(500) NULL,
  status               VARCHAR(16) NOT NULL,
  source               VARCHAR(32) NOT NULL DEFAULT 'USER_APPLY',
  reject_reason        VARCHAR(255) NULL,
  reviewed_by          BIGINT NULL,
  reviewed_at          DATETIME(3) NULL,
  result_occupant_id   BIGINT NULL,
  created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_community_status (community_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_change_application (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id      BIGINT NOT NULL,
  room_id           BIGINT NOT NULL,
  applicant_user_id BIGINT NOT NULL,
  change_type       VARCHAR(32) NOT NULL,
  payload_json      JSON NOT NULL,
  apply_message     VARCHAR(500) NULL,
  status            VARCHAR(16) NOT NULL,
  reject_reason     VARCHAR(255) NULL,
  reviewed_by       BIGINT NULL,
  reviewed_at       DATETIME(3) NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_community_status (community_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attachment (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id  BIGINT NULL,
  biz_type      VARCHAR(32) NOT NULL,
  biz_id        BIGINT NOT NULL,
  file_name     VARCHAR(255) NOT NULL,
  content_type  VARCHAR(128) NULL,
  size_bytes    BIGINT NOT NULL,
  storage_key   VARCHAR(512) NOT NULL,
  url           VARCHAR(512) NULL,
  uploaded_by   BIGINT NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS import_batch (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id     BIGINT NOT NULL,
  file_name        VARCHAR(255) NOT NULL,
  status           VARCHAR(16) NOT NULL,
  success_count    INT NOT NULL DEFAULT 0,
  fail_count       INT NOT NULL DEFAULT 0,
  fail_detail_json JSON NULL,
  created_by       BIGINT NOT NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NULL,
  actor_id     BIGINT NOT NULL,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(64) NULL,
  target_id    BIGINT NULL,
  detail_json  JSON NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS subscribe_notify_log (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NULL,
  user_id      BIGINT NOT NULL,
  scene        VARCHAR(64) NOT NULL,
  template_id  VARCHAR(128) NULL,
  status       VARCHAR(16) NOT NULL,
  error_msg    VARCHAR(512) NULL,
  biz_type     VARCHAR(32) NULL,
  biz_id       BIGINT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_scene (user_id, scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS todo_item (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_id BIGINT NULL,
  user_id      BIGINT NOT NULL,
  todo_type    VARCHAR(64) NOT NULL,
  title        VARCHAR(128) NOT NULL,
  content      VARCHAR(500) NULL,
  biz_type     VARCHAR(32) NULL,
  biz_id       BIGINT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  read_at      DATETIME(3) NULL,
  done_at      DATETIME(3) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 种子：平台 admin / admin（BCrypt）
INSERT INTO sys_user (username, password_hash, is_platform_admin, status, real_name)
SELECT 'admin', '$2a$10$hbOKrho1Jg3wq2FtPcl7oObLEPI0B7n9Iy62nekU5nSFgEx4x3vBG', 1, 1, '平台管理员'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
