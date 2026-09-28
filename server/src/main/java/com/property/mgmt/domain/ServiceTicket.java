package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报事报修统一表（单表承载 报修 + 投诉/表扬/咨询建议）。
 * <p>一张表内用 node_type 区分：
 * - TICKET   主工单（每条报事报修 1 行）
 * - REPLY    处理留言 / 物业回复（子行，parent_id 指向主工单）
 * - DISPATCH 派单 / 转派 / 接单流转（子行，parent_id 指向主工单）
 * kind 区分业务类型：REPAIR（公区报障/室内维修）、COMPLAINT（投诉/表扬/咨询建议）。
 */
@Data
@TableName("service_ticket")
public class ServiceTicket {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** TICKET 行为空；REPLY/DISPATCH 行为所属主工单 id */
    private Long parentId;
    /** TICKET / REPLY / DISPATCH */
    private String nodeType;
    /** REPAIR / COMPLAINT（仅 TICKET 行有意义） */
    private String kind;
    private Long communityId;
    private Long roomId;
    private Long applicantUserId;
    private String contactName;
    private String contactMobile;
    private String category;
    /** 公区报障位置 */
    private String location;
    /** 描述 / 留言内容 / 投诉内容 / 流转附言（按 node_type 取用） */
    private String content;
    private String status;
    /** 派单目标岗位（派给岗位时非空） */
    private String assigneeRole;
    private Long assigneeUserId;
    /** 投诉处理人 */
    private Long handlerUserId;
    private Integer rating;
    private String ratingComment;
    /** 评价维度：是否处理完成（1=是 0=否） */
    private Integer ratingResolved;
    /** 评价维度：响应及时度（1~5） */
    private Integer ratingResponse;
    /** 评价维度：处理及时情况（1~5） */
    private Integer ratingHandling;
    /** 评价维度：处理满意情况（1~5） */
    private Integer ratingSatisfaction;
    /** DISPATCH 节点动作：ASSIGN / TRANSFER / CLAIM */
    private String action;
    private Long fromUserId;
    private Long toUserId;
    /** REPLY 节点作者 */
    private Long authorUserId;
    private String toRole;
    private String remark;
    private LocalDateTime assignedAt;
    private LocalDateTime firstReplyAt;
    private Integer escalatedToManager;
    private Integer autoCompleted;
    private LocalDateTime completedAt;
    private LocalDateTime repliedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 投诉物业回复（仅列表展示用，非表字段，由查询时按最新 REPLY 子行填充） */
    @TableField(exist = false)
    private String replyContent;

    /** 房屋可读地址（仅展示用）：楼栋/单元/层/房号 */
    @TableField(exist = false)
    private String roomLabel;

    /** 指派人姓名（列表「处理岗」展示用） */
    @TableField(exist = false)
    private String assigneeName;

    /** 投诉处理人姓名（列表「处理岗」展示用） */
    @TableField(exist = false)
    private String handlerName;
}
