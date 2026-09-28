package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.TodoItem;
import com.property.mgmt.service.TodoNotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TodoController {

    private final TodoNotifyService todoNotifyService;

    @GetMapping("/todos")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status) {
        List<TodoItem> list = todoNotifyService.myTodos(status);
        return ApiResponse.ok(Map.of("list", list));
    }

    @PostMapping("/todos/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        todoNotifyService.markRead(id);
        return ApiResponse.ok();
    }

    @PostMapping("/todos/{id}/done")
    public ApiResponse<Void> done(@PathVariable Long id) {
        todoNotifyService.markDone(id);
        return ApiResponse.ok();
    }

    @PostMapping("/notify/subscribe/ack")
    public ApiResponse<Void> subscribeAck(@RequestBody Map<String, Object> body) {
        // 一阶段仅占位：授权记录可后续落库；不影响主流程
        return ApiResponse.ok();
    }
}
