package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 生成验证码并保存到 Redis。
     * @param phone 手机号
     */
    Result sendCode(String phone);
    /**
     * 校验验证码并返回 Redis 登录令牌。
     * @param loginForm 手机号和验证码
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 删除当前登录令牌。
     */
    Result logout(String token);

    /**
     * 查询用户公开信息，不返回手机号和密码。
     */
    Result queryUserById(Long id);

    /**
     * 记录当前用户当天的签到状态。
     */
    Result sign();

    /**
     * 统计本月截至今天的连续签到天数。
     */
    Result signCount();
}
