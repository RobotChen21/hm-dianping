package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if(json != null){
            return null;
        }
        R r = dbFallback.apply(id);
        if(r == null){
            //预防缓存穿透，防止数据库和Redis都不存在的大量数据访问打到数据库
            //1、将不存在的数据访问数据库时，把键值对放在Redis，然后值设置成null
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        set(key,r,time,unit);
        return r;
    }

    //使用逻辑过期解决缓存击穿的问题
    private <R,ID> R queryWithLogicalExpire(String keyPrefix,String lockKeyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix +id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        String redisDateJson = stringRedisTemplate.opsForValue().get(key);
        RedisData redisData = JSONUtil.toBean(redisDateJson, RedisData.class);
        R r = (R) redisData.getData();
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        String lockKey = lockKeyPrefix +id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启独立线程，然后重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(key,r1,time,unit);
                } finally {
                    releaseLock(lockKey);
                }
            });
        }
        return r;
    }

    //使用Redis互斥锁解决缓存击穿问题
    public  <R,ID> R queryWithMutex(String keyPrefix,String lockKeyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit) throws InterruptedException {
        String key = keyPrefix + id;
        //访问缓存
        String CacheStr = queryRedis(key);
        if (StrUtil.isNotBlank(CacheStr)) {
            return JSONUtil.toBean(CacheStr,type);
        }
        if(CacheStr != null){
            return null;
        }
        R rCache = null;
        //判断缓存是否命中
        //如果没有命中尝试获取锁
        String lockKey = lockKeyPrefix + id;
        while(true){
            if(tryLock(lockKey)){
                try {
                    // 获取锁后，再次检查缓存，避免更新缓存时已被其他请求处理
                    if(StrUtil.isNotBlank(CacheStr = queryRedis(key))){
                        rCache = JSONUtil.toBean(CacheStr,type);
                    }
                    if(rCache != null){
                        break;
                    }
                    rCache = dbFallback.apply(id);
                    if(rCache != null){
                        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(rCache));
                    }else {
                        set(key,"",time,TimeUnit.MINUTES);
                    }
                } finally {
                    releaseLock(lockKey);
                }
                break;
            }
            Thread.sleep(50);
            rCache = JSONUtil.toBean(queryRedis(key),type);
            if(rCache != null){
                break;
            }
        }
        return rCache;
    }
    private String queryRedis(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }
    private boolean tryLock(String key){
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
