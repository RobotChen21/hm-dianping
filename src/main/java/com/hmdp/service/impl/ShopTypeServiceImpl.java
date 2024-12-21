package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Override
    public List<ShopType> queryList() {
        String shopTypeKey = "shopType";
        String shopTypeJson = stringRedisTemplate.opsForValue().get(shopTypeKey);
        if(StrUtil.isNotBlank(shopTypeJson)){
            return JSONUtil.toList(shopTypeJson,ShopType.class);
        }
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        shopTypeJson = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(shopTypeKey,shopTypeJson);
        return shopTypes;
    }
}
