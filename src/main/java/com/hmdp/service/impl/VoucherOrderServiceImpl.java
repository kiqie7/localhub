package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Override
    public Result seckillVoucher(Long voucherId) {

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是有充足
        if (voucher.getStock()<1){
            return Result.fail("卖完了");
        }Long userId = UserHolder.getUser().getId();
        //创建锁对象(新增代码)
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            synchronized (userId.toString().intern()) {
                //获取代理对象
                IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
                return iVoucherOrderService.creatVoucher(voucherId);
            }
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result creatVoucher(Long voucherId) {
        //一人一单判断
        Long userId = UserHolder.getUser().getId();
        Integer count = query()
                .eq("voucher_id", voucherId)
                .eq("user_id", userId)
                .count();
            if (count > 0) {
                return Result.fail("用户已购买过一次");
            }

            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("出错了！");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 6.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 6.2.用户id
            voucherOrder.setUserId(userId);
            // 6.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(voucherOrder);

    }
}
