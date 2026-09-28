package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.config.WxProperties;
import com.property.mgmt.domain.SubscribeNotifyLog;
import com.property.mgmt.domain.TodoItem;
import com.property.mgmt.domain.UserWechat;
import com.property.mgmt.integration.WechatMiniApi;
import com.property.mgmt.mapper.SubscribeNotifyLogMapper;
import com.property.mgmt.mapper.TodoItemMapper;
import com.property.mgmt.mapper.UserWechatMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TodoNotifyService {

    /**
     * 物业侧待办类型：接收人为物业人员（客服/经理/保安等）。
     * 其余类型一律视为住户侧（业主/业主成员/租户/租户成员）的待办。
     */
    private static final Set<String> STAFF_TODO_TYPES = Set.of(
            "REPAIR_NEW",          // 新报修待派单
            "REPAIR_ASSIGNED",     // 报修已派单（派给本人物业岗）
            "COMPLAINT_NEW",       // 新投诉/咨询/表扬待处理
            "COMPLAINT_ASSIGNED",  // 投诉类已派单
            "PENDING_REVIEW",      // 住户认证 / 房屋变更待审核
            "REPAIR_ESCALATE"      // 报修超时升级提醒
    );


    private final TodoItemMapper todoItemMapper;
    private final SubscribeNotifyLogMapper subscribeNotifyLogMapper;
    private final UserWechatMapper userWechatMapper;
    private final WechatMiniApi wechatMiniApi;
    private final WxProperties wxProperties;

    @Transactional
    public TodoItem createTodo(Long communityId, Long userId, String todoType, String title,
                               String content, String bizType, Long bizId) {
        // 兜底去重：同一人 + 同一类型 + 同一业务已有 OPEN 则刷新标题/正文，不重复建
        TodoItem existing = todoItemMapper.selectOne(new LambdaQueryWrapper<TodoItem>()
                .eq(TodoItem::getUserId, userId)
                .eq(TodoItem::getTodoType, todoType)
                .eq(bizType != null, TodoItem::getBizType, bizType)
                .eq(bizId != null, TodoItem::getBizId, bizId)
                .eq(TodoItem::getStatus, "OPEN")
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setTitle(title);
            existing.setContent(content);
            existing.setUpdatedAt(now);
            todoItemMapper.updateById(existing);
            return existing;
        }
        TodoItem t = new TodoItem();
        t.setCommunityId(communityId);
        t.setUserId(userId);
        t.setTodoType(todoType);
        t.setTitle(title);
        t.setContent(content);
        t.setBizType(bizType);
        t.setBizId(bizId);
        t.setStatus("OPEN");
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        todoItemMapper.insert(t);
        return t;
    }

    /**
     * 尝试下发订阅消息并写日志。mock 或未配模板时记 SKIPPED。
     */
    @Transactional
    public void skipSubscribe(Long communityId, Long userId, String scene, String bizType, Long bizId, String reason) {
        SubscribeNotifyLog log = new SubscribeNotifyLog();
        log.setCommunityId(communityId);
        log.setUserId(userId);
        log.setScene(scene);
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setCreatedAt(LocalDateTime.now());

        UserWechat bind = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getUserId, userId)
                .eq(UserWechat::getAppId, wxProperties.getAppId())
                .last("LIMIT 1"));
        String openid = bind == null ? null : bind.getOpenid();
        WechatMiniApi.SendResult result = wechatMiniApi.sendSubscribe(
                openid, scene, reason == null ? scene : reason, reason, "pages/mine/mine");
        if ("SKIPPED".equals(result.status()) && reason != null && !reason.isBlank()
                && (result.errorMsg() == null || result.errorMsg().contains("mock"))) {
            // 保留调用方传入的业务说明
            log.setStatus("SKIPPED");
            log.setErrorMsg(reason + "；" + result.errorMsg());
        } else {
            log.setStatus(result.status());
            log.setErrorMsg(result.errorMsg());
        }
        subscribeNotifyLogMapper.insert(log);
    }

    public List<TodoItem> myTodos(String status) {
        AuthUser u = AuthContext.require();
        Long uid = u.getUserId();
        String identityType = u.getIdentityType();
        // 物业角色（含平台管理，本质是管理身份）只看物业待办；住户身份（业主/成员/租户）只看与本人相关的工单
        boolean staffScope = "STAFF".equals(identityType) || "PLATFORM".equals(identityType);
        LambdaQueryWrapper<TodoItem> q = new LambdaQueryWrapper<TodoItem>()
                .eq(TodoItem::getUserId, uid)
                .orderByDesc(TodoItem::getId);
        if (staffScope) {
            q.in(TodoItem::getTodoType, STAFF_TODO_TYPES);
        } else {
            q.notIn(TodoItem::getTodoType, STAFF_TODO_TYPES);
        }
        if (status != null && !status.isBlank()) {
            q.eq(TodoItem::getStatus, status);
        }
        return todoItemMapper.selectList(q);
    }

    @Transactional
    public void markRead(Long id) {
        TodoItem t = requireMine(id);
        t.setReadAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        todoItemMapper.updateById(t);
    }

    @Transactional
    public void markDone(Long id) {
        TodoItem t = requireMine(id);
        LocalDateTime now = LocalDateTime.now();
        t.setStatus("DONE");
        t.setDoneAt(now);
        if (t.getReadAt() == null) {
            t.setReadAt(now);
        }
        t.setUpdatedAt(now);
        todoItemMapper.updateById(t);
    }

    @Transactional
    public void doneByBiz(String bizType, Long bizId, String todoType) {
        List<TodoItem> list = todoItemMapper.selectList(new LambdaQueryWrapper<TodoItem>()
                .eq(TodoItem::getBizType, bizType)
                .eq(TodoItem::getBizId, bizId)
                .eq(todoType != null, TodoItem::getTodoType, todoType)
                .eq(TodoItem::getStatus, "OPEN"));
        LocalDateTime now = LocalDateTime.now();
        for (TodoItem t : list) {
            t.setStatus("DONE");
            t.setDoneAt(now);
            t.setUpdatedAt(now);
            todoItemMapper.updateById(t);
        }
    }

    /**
     * 关闭同一业务记录下全部未完成待办（同一详情页只保留最新一次通知时用）。
     */
    @Transactional
    public void doneAllByBiz(String bizType, Long bizId) {
        doneByBiz(bizType, bizId, null);
    }

    private TodoItem requireMine(Long id) {
        TodoItem t = todoItemMapper.selectById(id);
        if (t == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "待办不存在");
        }
        if (!AuthContext.require().getUserId().equals(t.getUserId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权操作该待办");
        }
        return t;
    }
}
