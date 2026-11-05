package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 恢复当前请求的用户信息并续期，不负责强制登录。
 */
public class RefreshTokenInterceptor implements AsyncHandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 每次请求从空上下文开始，避免复用线程时读到旧用户。
        UserHolder.removeUser();

        // 前端通过 authorization 请求头直接传递 token，不带 Bearer 前缀。
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }

        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (userMap.isEmpty()) {
            return true;
        }

        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        if (userDTO.getId() == null) {
            return true;
        }

        // 先续期再保存用户：Redis 异常时不会遗留 ThreadLocal。
        // key 可能在读取 Hash 后过期；续期失败时按未登录处理。
        if (Boolean.TRUE.equals(stringRedisTemplate.expire(
                tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES))) {
            UserHolder.saveUser(userDTO);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserHolder.removeUser();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        // 异步请求释放原 Servlet 线程时也必须清理；不向工作线程自动传递用户。
        UserHolder.removeUser();
    }
}
