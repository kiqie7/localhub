package com.hmdp.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 启动时从真实数据库重建 GEO 索引，代替教程中的测试导入。
 * 可用 localhub.geo.initialize-on-startup=false 关闭；关闭前应保证 GEO 数据已准备好。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "localhub.geo.initialize-on-startup", havingValue = "true", matchIfMissing = true)
public class ShopGeoInitializer implements ApplicationRunner {

    private static final int BATCH_SIZE = 500;
    private static final DefaultRedisScript<Long> PUBLISH_INDEX_SCRIPT = new DefaultRedisScript<>();

    static {
        // 在同一条原子操作中替换单个类型的索引，并移除临时 key 的过期时间。
        PUBLISH_INDEX_SCRIPT.setScriptText("redis.call('rename', KEYS[1], KEYS[2]); "
                + "redis.call('persist', KEYS[2]); return 1;");
        PUBLISH_INDEX_SCRIPT.setResultType(Long.class);
    }
    private final ShopMapper shopMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public ShopGeoInitializer(ShopMapper shopMapper, StringRedisTemplate stringRedisTemplate) {
        this.shopMapper = shopMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> previousKeys = findShopGeoKeys();
        Map<Long, String> temporaryKeys = new LinkedHashMap<>();
        String rebuildId = UUID.randomUUID().toString();
        long lastId = 0;
        long indexed = 0;
        long skipped = 0;
        try {
            while (true) {
                // 主键游标分批读取，避免启动时一次把全部商铺载入内存。
                List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>()
                        .select("id", "type_id", "x", "y").gt("id", lastId)
                        .orderByAsc("id").last("LIMIT " + BATCH_SIZE));
                if (shops.isEmpty()) {
                    break;
                }
                Map<Long, Map<String, Point>> locationsByType = new HashMap<>();
                for (Shop shop : shops) {
                    lastId = shop.getId();
                    if (!hasValidLocation(shop)) {
                        skipped++;
                        continue;
                    }
                    locationsByType.computeIfAbsent(shop.getTypeId(), ignored -> new HashMap<>())
                            .put(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
                    indexed++;
                }
                for (Map.Entry<Long, Map<String, Point>> entry : locationsByType.entrySet()) {
                    String temporaryKey = temporaryKeys.computeIfAbsent(entry.getKey(),
                            typeId -> SHOP_GEO_KEY + "rebuild:" + rebuildId + ":" + typeId);
                    stringRedisTemplate.opsForGeo().add(temporaryKey, entry.getValue());
                    // 异常终止时临时索引自行过期，正式索引不受导入中途失败影响。
                    stringRedisTemplate.expire(temporaryKey, 1, TimeUnit.HOURS);
                }
            }
            for (Map.Entry<Long, String> entry : temporaryKeys.entrySet()) {
                String key = SHOP_GEO_KEY + entry.getKey();
                stringRedisTemplate.execute(PUBLISH_INDEX_SCRIPT, Arrays.asList(entry.getValue(), key));
                previousKeys.remove(key);
            }
            // 只清理由本项目约定的 shop:geo:<数字类型ID>，不触碰业务数据或其他 Redis key。
            if (!previousKeys.isEmpty()) {
                stringRedisTemplate.delete(previousKeys);
            }
            log.info("商铺 GEO 初始化完成，已索引 {} 家，跳过坐标无效数据 {} 家", indexed, skipped);
        } finally {
            if (!temporaryKeys.isEmpty()) {
                try {
                    stringRedisTemplate.delete(new HashSet<>(temporaryKeys.values()));
                } catch (RuntimeException cleanupError) {
                    // 临时 key 已有 TTL，清理失败不覆盖初始化的原始异常。
                    log.warn("清理商铺 GEO 临时索引失败，等待其自动过期", cleanupError);
                }
            }
        }
    }

    private Set<String> findShopGeoKeys() {
        Set<String> keys = stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(SHOP_GEO_KEY + "*").count(100).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);
                    String typeId = key.substring(SHOP_GEO_KEY.length());
                    if (typeId.matches("\\d+")) {
                        result.add(key);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取商铺 GEO 索引失败", e);
            }
            return result;
        });
        return keys == null ? new HashSet<>() : keys;
    }

    private boolean hasValidLocation(Shop shop) {
        Double x = shop.getX();
        Double y = shop.getY();
        return shop.getTypeId() != null && shop.getTypeId() > 0 && x != null && y != null
                && Double.isFinite(x) && Double.isFinite(y)
                && x >= -180 && x <= 180 && y >= -85.05112878 && y <= 85.05112878;
    }
}
