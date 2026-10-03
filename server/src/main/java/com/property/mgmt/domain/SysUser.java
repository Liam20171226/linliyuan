package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String mobile;
    private String realName;
    private String idCardNo;
    private String passwordHash;
    /** App 游客等场景下供平台查看的明文密码（最长 10 位）；登录仍用 passwordHash */
    private String passwordPlain;
    /** APP_GUEST = App 自行注册游客 */
    private String registerSource;
    /** 1 = 平台下发临时密码，首次登录须修改 */
    private Integer mustChangePassword;
    private Integer isPlatformAdmin;
    private Integer status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
