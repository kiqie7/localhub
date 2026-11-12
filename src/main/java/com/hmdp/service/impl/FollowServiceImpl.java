package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (followUserId == null || followUserId <= 0 || isFollow == null) {
            return Result.fail("关注参数不正确");
        }
        if (isFollow && user.getId().equals(followUserId)) {
            return Result.fail("不能关注自己");
        }
        if (isFollow && userService.getById(followUserId) == null) {
            return Result.fail("用户不存在");
        }

        Long userId = user.getId();
        String key = FOLLOW_KEY + userId;
        // 同一用户的关注变更与缓存重建共用锁，重复关注不会插入第二条记录。
        RLock lock = followCacheLock(userId);
        lock.lock();
        try {
            // 先使旧缓存失效。数据库写入后即使缓存重建失败，下次也能从数据库恢复。
            stringRedisTemplate.delete(key);
            if (isFollow) {
                if (!hasFollowRelation(userId, followUserId)) {
                    Follow follow = new Follow();
                    follow.setUserId(userId);
                    follow.setFollowUserId(followUserId);
                    if (!save(follow)) {
                        return Result.fail("关注失败");
                    }
                }
            } else {
                // 删除全部匹配关系，也能清理历史重复记录；已经取关则保持成功。
                remove(new QueryWrapper<Follow>()
                        .eq("user_id", userId).eq("follow_user_id", followUserId));
                if (hasFollowRelation(userId, followUserId)) {
                    return Result.fail("取消关注失败");
                }
            }
            restoreFollowCache(userId);
            return Result.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (followUserId == null || followUserId <= 0) {
            return Result.fail("用户id不正确");
        }
        return Result.ok(hasFollowRelation(user.getId(), followUserId));
    }

    @Override
    public Result followCommons(Long otherUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (otherUserId == null || otherUserId <= 0) {
            return Result.fail("用户id不正确");
        }
        ensureFollowCache(user.getId());
        ensureFollowCache(otherUserId);
        Set<String> common = stringRedisTemplate.opsForSet()
                .intersect(FOLLOW_KEY + user.getId(), FOLLOW_KEY + otherUserId);
        if (common == null || common.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(common.size());
        for (String id : common) {
            ids.add(Long.valueOf(id));
        }
        List<UserDTO> users = new ArrayList<>();
        for (User followedUser : userService.listByIds(ids)) {
            users.add(BeanUtil.copyProperties(followedUser, UserDTO.class));
        }
        users.sort(Comparator.comparing(UserDTO::getId));
        return Result.ok(users);
    }

    private boolean hasFollowRelation(Long userId, Long followUserId) {
        return query().eq("user_id", userId).eq("follow_user_id", followUserId).count() > 0;
    }

    private RLock followCacheLock(Long userId) {
        return redissonClient.getLock("lock:follows:" + userId);
    }

    private void ensureFollowCache(Long userId) {
        String key = FOLLOW_KEY + userId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        RLock lock = followCacheLock(userId);
        lock.lock();
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                restoreFollowCache(userId);
            }
        } finally {
            lock.unlock();
        }
    }

    // Redis Set 不保存空集合；没有任何关注时，后续仍可安全回源确认。
    private void restoreFollowCache(Long userId) {
        List<Follow> follows = query().eq("user_id", userId).list();
        Set<String> ids = new HashSet<>();
        for (Follow follow : follows) {
            if (follow.getFollowUserId() != null) {
                ids.add(follow.getFollowUserId().toString());
            }
        }
        if (!ids.isEmpty()) {
            stringRedisTemplate.opsForSet().add(FOLLOW_KEY + userId, ids.toArray(new String[0]));
        }
    }
}
