-- 工单派单 / 转派流转记录：追溯每一次「谁转给谁、附言、时间」
CREATE TABLE IF NOT EXISTS repair_dispatch (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  repair_order_id BIGINT NOT NULL,
  from_user_id   BIGINT NULL COMMENT '派单人，首次派单可为空',
  to_user_id     BIGINT NOT NULL COMMENT '接收人',
  remark         VARCHAR(500) NULL COMMENT '派单附言',
  action         VARCHAR(20) NOT NULL DEFAULT 'ASSIGN' COMMENT 'ASSIGN 首次派单 / TRANSFER 转派',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_dispatch_order (repair_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
