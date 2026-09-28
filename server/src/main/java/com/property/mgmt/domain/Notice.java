package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notice")
public class Notice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String title;
    private String content;
    private String noticeType;
    private Integer urgency;
    private String visibility;
    private LocalDateTime effectiveAt;
    private LocalDateTime expireAt;
    private Long createdBy;
    private String creatorIdentity;
    /** 封面图附件 id，小程序轮播用 */
    private Long coverAttachmentId;
    /** 1=上首页 Banner（须有封面） */
    private Integer showOnBanner;
    /** Banner 顺序 1~5；非空即上 Banner，为空不上 */
    private Integer bannerOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
