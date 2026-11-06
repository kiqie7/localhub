package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }


    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺不存在");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    private void saveShop2Redis (Long id,Long expireSeconds){
        //1.查询商铺
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
    //自定义一个线程池用于缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire (Long id){

        String key = CACHE_SHOP_KEY + id ;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isBlank(json)){
            return null;
        }
        //反序列化为redisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //3.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        //4.尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{

                try {
                    this.saveShop2Redis(id,20L);
                    return ;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }

    private Shop queryWithMutex(Long id) throws InterruptedException {

        String key = CACHE_SHOP_KEY + id ;
        while (true) {
            //1.查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //2.判断缓存是否命中
            if (StrUtil.isNotBlank(shopJson)) {
                //3.命中直接返回数据
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 上面的判断已经排除了正常数据
            if (shopJson != null) {
                // 此时命中的是之前写入的空字符串，说明数据库中不存在该商铺
                return null;
            }
            //4.未命中尝试获取锁
            String lockKey = LOCK_SHOP_KEY + id;
            if (!tryLock(lockKey)) {
                Thread.sleep(50);
                continue;
            }
            try {
                String shopJson1 = stringRedisTemplate.opsForValue().get(key);
                //2.判断缓存是否命中
                if (StrUtil.isNotBlank(shopJson1)) {
                    //3.命中直接返回数据
                    return JSONUtil.toBean(shopJson1, Shop.class);
                }
                // 上面的判断已经排除了正常数据
                if (shopJson1 != null) {
                    // 此时命中的是之前写入的空字符串，说明数据库中不存在该商铺
                    //空值缓存
                    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    return null;
                }
                Shop shop = getById(id);
                if (shop == null) {
                    return null;
                }
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            } finally {
                unLock(lockKey);
            }
        }
    }


    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }






















}
