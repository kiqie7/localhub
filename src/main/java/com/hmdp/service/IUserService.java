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
}
