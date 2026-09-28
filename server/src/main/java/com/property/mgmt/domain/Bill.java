package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bill")
public class Bill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private String billMonth;
    private String status;
    private LocalDate dueDate;
    private BigDecimal totalAmount;
    private String payChannel;
    private LocalDateTime paidAt;
    private Long confirmedBy;
    private String wechatTransactionId;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
