package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号输入错误");
        }
        String code = RandomUtil.randomNumbers(6);
        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        ops.set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES) ;
//        session.setAttribute("code",code);
//        session.setAttribute("phone",phone);
        log.info("短信验证码{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号输入错误");
        }
//        Object cacheCode = session.getAttribute("code");
//        Object cachePhone = session.getAttribute("phone");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
//        if(cachePhone == null || cacheCode ==null){
//            return Result.fail("验证码已经过期");
//        }
//        if(!cachePhone.toString().equals(phone) || !cacheCode.toString().equals(code)){
//            return Result.fail("手机号或者验证码出错");
//        }
        if(cacheCode == null){
            return Result.fail("验证码已经过期");
        }
        if(!cacheCode.equals(code)){
            return Result.fail("手机号或者验证码出错");
        }
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if(user == null){
            user = createUserWithPhone(phone);
            save(user);
        }
//        session.setAttribute("user",user);
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        String tokenKey = LOGIN_USER_KEY + token;
//        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,BeanUtil.beanToMap(userDTO));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,BeanUtil.beanToMap(userDTO,
        new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString())));
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setCreateTime(LocalDateTime.now());
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));
        user.setUpdateTime(LocalDateTime.now());
        return user;
    }
}
