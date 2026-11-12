package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.SystemConstants.BUSINESS_ZONE;
import static com.hmdp.utils.SystemConstants.MAX_PAGE_SIZE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FEED_PAGE_SIZE = 2;

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (blog == null) {
            return Result.fail("笔记内容不能为空");
        }
        // 作者、计数和发布时间由服务端决定，不接受客户端伪造。
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        blog.setId(null);
        blog.setUserId(user.getId());
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(now);
        blog.setUpdateTime(now);
        if (!save(blog) || blog.getId() == null) {
            return Result.fail("新增笔记失败");
        }

        // 先完成数据库保存，再将同一条笔记推送到所有粉丝的收件箱。
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        long publishTime = System.currentTimeMillis();
        Set<Long> followerIds = new HashSet<>();
        for (Follow follow : follows) {
            if (follow.getUserId() != null) {
                followerIds.add(follow.getUserId());
            }
        }
        for (Long followerId : followerIds) {
            stringRedisTemplate.opsForZSet().add(
                    FEED_KEY + followerId, blog.getId().toString(), publishTime);
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记id不正确");
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        fillBlogDetails(Collections.singletonList(blog));
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query().orderByDesc("liked").orderByDesc("id")
                .page(new Page<>(pageNumber(current), MAX_PAGE_SIZE));
        fillBlogDetails(page.getRecords());
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        return queryBlogByUserId(user.getId(), current);
    }

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        if (userId == null || userId <= 0) {
            return Result.fail("用户id不正确");
        }
        Page<Blog> page = query().eq("user_id", userId).orderByDesc("id")
                .page(new Page<>(pageNumber(current), MAX_PAGE_SIZE));
        fillBlogDetails(page.getRecords());
        return Result.ok(page.getRecords());
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (id == null || id <= 0) {
            return Result.fail("笔记id不正确");
        }
        // 同一用户对同一笔记的切换串行执行，避免两个请求同时判断为未点赞。
        RLock lock = redissonClient.getLock("lock:blog:like:" + id + ":" + user.getId());
        lock.lock();
        try {
            if (getById(id) == null) {
                return Result.fail("笔记不存在");
            }
            String key = BLOG_LIKED_KEY + id;
            String member = user.getId().toString();
            Double score = stringRedisTemplate.opsForZSet().score(key, member);
            if (score == null) {
                boolean success = update().setSql("liked = COALESCE(liked, 0) + 1")
                        .eq("id", id).update();
                if (!success) {
                    return Result.fail("点赞失败");
                }
                stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
            } else {
                // 计数已为0时也清除残留的点赞标记，且不让无符号字段减成负数。
                update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
                stringRedisTemplate.opsForZSet().remove(key, member);
            }
            return Result.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记id不正确");
        }
        Set<String> firstFive = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (firstFive == null || firstFive.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(firstFive.size());
        for (String member : firstFive) {
            ids.add(Long.valueOf(member));
        }
        Map<Long, User> users = usersById(ids);
        List<UserDTO> result = new ArrayList<>();
        // IN 查询不保证返回顺序，按 ZSET 的点赞时间顺序组装前5位用户。
        for (Long userId : ids) {
            User user = users.get(userId);
            if (user != null) {
                result.add(BeanUtil.copyProperties(user, UserDTO.class));
            }
        }
        return Result.ok(result);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (max == null || max < 0 || offset == null || offset < 0) {
            return Result.fail("分页参数不正确");
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + user.getId(), 0, max, offset, FEED_PAGE_SIZE);
        ScrollResult result = new ScrollResult();
        result.setList(Collections.emptyList());
        result.setMinTime(max);
        result.setOffset(offset);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok(result);
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int sameTimeCount = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                sameTimeCount++;
            } else {
                minTime = time;
                sameTimeCount = 1;
            }
        }
        // 若这一页仍停留在上次时间戳，跳过数量必须累计；进入更早时间戳才重置。
        int nextOffset = minTime == max.longValue() ? offset + sameTimeCount : sameTimeCount;
        Map<Long, Blog> blogsById = new HashMap<>();
        for (Blog blog : listByIds(ids)) {
            blogsById.put(blog.getId(), blog);
        }
        List<Blog> blogs = new ArrayList<>();
        for (Long blogId : ids) {
            Blog blog = blogsById.get(blogId);
            if (blog != null) {
                blogs.add(blog);
            }
        }
        fillBlogDetails(blogs);
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(nextOffset);
        return Result.ok(result);
    }

    private void fillBlogDetails(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        Set<Long> authorIds = new HashSet<>();
        for (Blog blog : blogs) {
            if (blog.getUserId() != null) {
                authorIds.add(blog.getUserId());
            }
        }
        Map<Long, User> authors = usersById(authorIds);
        UserDTO viewer = UserHolder.getUser();
        for (Blog blog : blogs) {
            User author = authors.get(blog.getUserId());
            if (author != null) {
                blog.setName(author.getNickName());
                blog.setIcon(author.getIcon());
            }
            boolean liked = viewer != null && viewer.getId() != null
                    && stringRedisTemplate.opsForZSet().score(
                    BLOG_LIKED_KEY + blog.getId(), viewer.getId().toString()) != null;
            blog.setIsLike(liked);
        }
    }

    private Map<Long, User> usersById(Collection<Long> ids) {
        Map<Long, User> users = new HashMap<>();
        if (ids.isEmpty()) {
            return users;
        }
        for (User user : userService.listByIds(ids)) {
            users.put(user.getId(), user);
        }
        return users;
    }

    private long pageNumber(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }
}
