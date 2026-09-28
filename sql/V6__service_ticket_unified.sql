-- 报事报修统一为单表 service_ticket（迁移：删除旧的 repair_order / repair_reply / repair_dispatch / complaint）
DROP TABLE IF EXISTS repair_reply;
DROP TABLE IF EXISTS repair_dispatch;
DROP TABLE IF EXISTS repair_order;
DROP TABLE IF EXISTS complaint;

CREATE TABLE service_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT,
  node_type VARCHAR(16) NOT NULL,
  kind VARCHAR(16),
  community_id BIGINT NOT NULL,
  room_id BIGINT,
  applicant_user_id BIGINT,
  contact_name VARCHAR(64),
  contact_mobile VARCHAR(20),
  category VARCHAR(32),
  location VARCHAR(128),
  content VARCHAR(1000),
  status VARCHAR(32),
  assignee_role VARCHAR(32),
  assignee_user_id BIGINT,
  handler_user_id BIGINT,
  rating TINYINT,
  rating_comment VARCHAR(255),
  action VARCHAR(20),
  from_user_id BIGINT,
  to_user_id BIGINT,
  author_user_id BIGINT,
  to_role VARCHAR(32),
  remark VARCHAR(500),
  assigned_at DATETIME,
  first_reply_at DATETIME,
  escalated_to_manager TINYINT NOT NULL DEFAULT 0,
  auto_completed TINYINT NOT NULL DEFAULT 0,
  completed_at DATETIME,
  replied_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_parent (parent_id),
  INDEX idx_ticket_community (community_id),
  INDEX idx_ticket_kind (kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
