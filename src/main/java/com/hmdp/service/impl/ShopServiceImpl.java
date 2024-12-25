package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        Shop shop;
        try {
            shop = queryWithMutex(id);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            Result.fail("店铺id不能为空");
        }
        //先更改数据库，然后再删除缓存，有利于线程安全
        updateById(shop);
        String shopKey = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }

    private boolean tryLock(String key){
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
//    public Shop queryWithPassThrough(Long id){
//        String shopKey = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
//        if(StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson,Shop.class);
//        }
//        if(shopJson != null){
//            return null;
//        }
//        Shop shop = getById(id);
//        if(shop == null){
//            //预防缓存穿透，防止数据库和Redis都不存在的大量数据访问打到数据库
//            //1、将不存在的数据访问数据库时，把键值对放在Redis，然后值设置成null
//            stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }
    //解决缓存击穿问题
    private Shop queryWithMutex(Long id) throws InterruptedException {
        String shopKey = CACHE_SHOP_KEY + id;
        //访问缓存
        String shopCacheStr = queryShopRedis(shopKey);
        if (StrUtil.isNotBlank(shopCacheStr)) {
            return JSONUtil.toBean(shopCacheStr,Shop.class);
        }
        if(shopCacheStr != null){
            return null;
        }
        Shop shopCache = null;
        //判断缓存是否命中
        //如果没有命中尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        while(true){
            if(tryLock(lockKey)){
                try {
                    // 获取锁后，再次检查缓存，避免更新缓存时已被其他请求处理
                    if(StrUtil.isNotBlank(shopCacheStr = queryShopRedis(shopKey))){
                        shopCache = JSONUtil.toBean(shopCacheStr,Shop.class);
                    }
                    if(shopCache != null){
                        break;
                    }
                    shopCache = getById(id);
                    if(shopCache != null){
                        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shopCache));
                    }else {
                        stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    }
                } finally {
                    releaseLock(lockKey);
                }
                break;
            }
            Thread.sleep(50);
            shopCache = JSONUtil.toBean(queryShopRedis(shopKey),Shop.class);
            if(shopCache != null){
                break;
            }
        }
        return shopCache;
    }

    private Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY +id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        String redisDateJson = stringRedisTemplate.opsForValue().get(key);
        RedisData redisData = JSONUtil.toBean(redisDateJson, RedisData.class);
        Shop shop = (Shop) redisData.getData();
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return shop;
        }
        String lockKey = LOCK_SHOP_KEY +id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启独立线程，然后重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShop2Redis(id,20L);
                } finally {
                    releaseLock(lockKey);
                }
            });
        }
        return shop;
    }



    private void saveShop2Redis(Long id, Long expireSeconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private String queryShopRedis(String shopKey){
        return stringRedisTemplate.opsForValue().get(shopKey);
    }
}
