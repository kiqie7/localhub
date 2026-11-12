package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IStatisticsService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.UV_KEY_PREFIX;
import static com.hmdp.utils.SystemConstants.BUSINESS_ZONE;

@Service
public class StatisticsServiceImpl implements IStatisticsService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result recordVisit(String visitorId) {
        UserDTO user = UserHolder.getUser();
        // 优先使用稳定的浏览器标识，让登录前后可保持同一统计口径。
        String member;
        if (StrUtil.isNotBlank(visitorId)) {
            if (visitorId.length() > 128) {
                return Result.fail("访客标识不能超过128个字符");
            }
            member = "visitor:" + visitorId;
        } else if (user != null) {
            member = "user:" + user.getId();
        } else {
            return Result.fail("匿名访问需要提供稳定的访客标识");
        }
        String key = UV_KEY_PREFIX + LocalDate.now(BUSINESS_ZONE).format(DATE_FORMAT);
        stringRedisTemplate.opsForHyperLogLog().add(key, member);
        return Result.ok();
    }

    @Override
    public Result queryUv(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(BUSINESS_ZONE) : date;
        String key = UV_KEY_PREFIX + day.format(DATE_FORMAT);
        Long count = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(count == null ? 0L : count);
    }
}
