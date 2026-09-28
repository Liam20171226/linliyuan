package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.domain.UserWechat;
import com.property.mgmt.mapper.UserWechatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信与账号的绑定维护。
 * 绑定仅在小程序「微信登录 + 手机号授权匹配」成功时建立；
 * 平台/物业后台改手机号时须解绑，迫使用户用新号重新授权。
 */
@Service
@RequiredArgsConstructor
public class WechatBindService {

    private final UserWechatMapper userWechatMapper;

    /**
     * 清除该用户在本系统下的全部微信绑定。返回删除行数。
     */
    @Transactional
    public int unbindAllForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        return userWechatMapper.delete(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getUserId, userId));
    }
}
