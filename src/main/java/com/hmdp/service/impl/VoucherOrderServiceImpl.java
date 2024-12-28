package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private IVoucherService voucherService;
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher == null){
            return Result.fail("没有查询到这个优惠卷");
        }
        if(LocalDateTime.now().isBefore(voucher.getBeginTime())){
            return Result.fail("秒杀尚未开始");
        }
        if(LocalDateTime.now().isAfter(voucher.getEndTime())){
            return Result.fail("秒杀已经结束");
        }
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //new的对象里面，里面的Autowired不会进行自动注入
        SimpleRedisLock lock = new SimpleRedisLock("order:"+userId.toString(),stringRedisTemplate);
        if (!lock.tryLock(1200L)) {
            return Result.fail("一个用户同一时间只能抢一个优惠卷");
        }
        try {
            //这里调用的是本对象的事物，不是代理对象，所以会导致事物失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }
    //spring实现事物的方法是给指定对象简历代理对象，代理对象有事物
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("用户已经买过了");
        }
        //利用CAS乐观锁，乐观锁适合并发量不大的情况
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if(!success){
            return Result.fail("库存不足！");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
