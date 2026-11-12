package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    @Transactional
    public Result saveShop(Shop shop) {
        if (shop == null || shop.getTypeId() == null || shop.getTypeId() <= 0
                || !isValidCoordinate(shop.getX(), shop.getY())) {
            return Result.fail("商铺类型或经纬度不正确");
        }
        if (!save(shop)) {
            return Result.fail("新增商铺失败");
        }
        // 新增后同步索引，不需要重启应用才能出现在附近商户列表。
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + shop.getTypeId(),
                new Point(shop.getX(), shop.getY()), shop.getId().toString());
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop == null) {
            return Result.fail("商铺信息不能为空");
        }
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺不存在");
        }
        Shop previous = getById(id);
        if (previous == null) {
            return Result.fail("商铺不存在");
        }
        Long typeId = shop.getTypeId() == null ? previous.getTypeId() : shop.getTypeId();
        Double x = shop.getX() == null ? previous.getX() : shop.getX();
        Double y = shop.getY() == null ? previous.getY() : shop.getY();
        if (typeId == null || typeId <= 0 || !isValidCoordinate(x, y)) {
            return Result.fail("商铺类型或经纬度不正确");
        }
        //1.更新数据库
        if (!updateById(shop)) {
            return Result.fail("更新商铺失败");
        }
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        //3.更新坐标；类型改变时移除旧类型中的成员。
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId, new Point(x, y), id.toString());
        if (!Objects.equals(previous.getTypeId(), typeId) && previous.getTypeId() != null) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + previous.getTypeId(), id.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || typeId <= 0 || current == null || current < 1) {
            return Result.fail("商铺类型或页码不正确");
        }
        // 未提供完整坐标时，保持原有数据库分页行为。
        if (x == null || y == null) {
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        if (!isValidCoordinate(x, y)) {
            return Result.fail("经纬度不正确");
        }

        long end = (long) current * SystemConstants.DEFAULT_PAGE_SIZE;
        long from = end - SystemConstants.DEFAULT_PAGE_SIZE;
        // Spring Data Redis 2.3 使用 GEORADIUS，兼容现有依赖，无需升级到 GEOSEARCH。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                SHOP_GEO_KEY + typeId,
                new Circle(new Point(x, y), new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance().sortAscending().limit(end));
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> locations = results.getContent();
        List<Long> ids = new ArrayList<>(SystemConstants.DEFAULT_PAGE_SIZE);
        Map<Long, Double> distances = new HashMap<>(SystemConstants.DEFAULT_PAGE_SIZE);
        for (int index = (int) from; index < locations.size(); index++) {
            GeoResult<RedisGeoCommands.GeoLocation<String>> location = locations.get(index);
            Long shopId = Long.valueOf(location.getContent().getName());
            ids.add(shopId);
            distances.put(shopId, location.getDistance().getValue());
        }
        // ID 均来自 Long，FIELD 中不拼接原始请求字符串；保持 Redis 距离排序。
        String idString = StrUtil.join(",", ids);
        List<Shop> shops = query().eq("type_id", typeId).in("id", ids)
                .last("ORDER BY FIELD(id," + idString + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distances.get(shop.getId()));
        }
        return Result.ok(shops);
    }

    private boolean isValidCoordinate(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y)
                && x >= -180 && x <= 180 && y >= -85.05112878 && y <= 85.05112878;
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
