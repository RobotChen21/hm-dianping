package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    String lockName;
    String lockKey;
    String threadName;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID()+ "-";
    //创建lua脚本，把判断和释放锁当成原子
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("script/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String lockName,StringRedisTemplate stringRedisTemplate) {
        this.lockName = lockName;
        this.stringRedisTemplate = stringRedisTemplate;
        lockKey = KEY_PREFIX + lockName;
        threadName = ID_PREFIX + Thread.currentThread().getName();
    }

    @Override
    public Boolean tryLock(Long timeoutSec) {
        return stringRedisTemplate
                .opsForValue()
                .setIfAbsent(lockKey,threadName,timeoutSec, TimeUnit.SECONDS);
    }

    @Override
    public void unlock() {
        //使用lua脚本进行执行释放锁的操作
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                threadName
        );
    }
//    @Override
//    public void unlock() {
//        String value = stringRedisTemplate.opsForValue().get(lockKey);
//        //只能释放自己的锁，不能释放别人的锁
//        //这种情况是防止业务还没完，锁自动过期，其他线程抢走了锁，然后把别人的锁给释放了
//        if (value == null) return;
//        //判断和删除必须设置成原子性。不然先判断完之后JVM进行垃圾回收进行阻塞，又会发生线程安全问题
//        if (threadName.equals(value)) stringRedisTemplate.delete(lockKey);
//    }
}
