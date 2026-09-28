package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("about_us")
public class AboutUs {
    @TableId
    private Long id;
    private String title;
    private String content;
    private LocalDateTime updatedAt;
}
