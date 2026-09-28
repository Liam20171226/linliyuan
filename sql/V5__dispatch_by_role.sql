-- 工单派单改为「派给岗位」：客服/经理把工单派到岗位（如保安岗），
-- 该岗位员工在小程序接单（CLAIM）后成为处理人。
-- repair_order 增加当前处理岗
ALTER TABLE repair_order
  ADD COLUMN assignee_role VARCHAR(32) NULL COMMENT '当前处理岗（派给岗位时非空）' AFTER status;

-- repair_dispatch 增加目标岗位；to_user_id 改为可空（按岗位派单时为空，接单后记录接单人）
ALTER TABLE repair_dispatch
  ADD COLUMN to_role VARCHAR(32) NULL COMMENT '接收岗位' AFTER to_user_id,
  MODIFY COLUMN to_user_id BIGINT NULL COMMENT '接收人，按岗位派单时为空';

-- action 语义扩展：ASSIGN 首次派单 / TRANSFER 转派 / CLAIM 岗位接单
ALTER TABLE repair_dispatch
  MODIFY COLUMN action VARCHAR(20) NOT NULL DEFAULT 'ASSIGN' COMMENT 'ASSIGN 派单 / TRANSFER 转派 / CLAIM 接单';
