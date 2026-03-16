package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.hmdp.utils.RedisConstants.*;


@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 设置缓存
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期缓存
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询：通过缓存空对象解决缓存穿透
     */
    public <T,ID> T queryWithPathThrough(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallBack,
                                         Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)) {
            //长度为0或null都是isBlank，所以长度不为零且不为null的Json才会进入到if当中
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }

        //3.判断命中的值是否为空值（穿透）
        if(Json != null) {
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        //由于不知道数据库的返回类型，因此交由调用者处理
        //T t = getById(id);
        T t = dbFallBack.apply(id);

        //5.数据库不存在，返回错误
        if(t == null) {
            //将空值写入Redis（解决穿透）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //6.存在，写入Redis并设置超时时间
        this.set(key, t, time, unit);

        return t;
    }

    /**
     * 根据id查询:用逻辑过期方法解决缓存击穿问题
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <T,ID> T queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<T> type, Function<ID,T> dbFallBack,
                                           Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isBlank(Json)) {
            //3.缓存未命中，直接返回null（长度为0或者为null都会进入if当中）
            return null;
        }

        //4.命中，将数据从Redis反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return t;
        }

        //5.2 已过期，缓存重建

        //6.缓存重建

        //6.1 获取互斥锁
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);

        //6.2 判断是否获取锁成功
        if(isLock) {
            //6.3 成功，开启独立线程，实现缓存重建
            //注意：获取锁成功应该再次检测Redis缓存是否过期，如果存在则无需重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存

                    //6.3.1 查询数据库
                    T t1 = dbFallBack.apply(id);

                    //6.3.2 写入redis
                    this.setWithLogicalExpire(key, t1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4 失败，直接返回过期的信息
        return t;
    }


    /**
     * 根据id查询：用互斥锁方法解决缓存击穿问题
     */
    public <T,ID> T queryWithMutex(String keyPrefix, String lockPrefix, ID id, Class<T> type, Function<ID,T> dbFallBack,
                                   Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)) {
            //长度为0和null都是isBlank，所以长度不为零的或不为null的Json才会进入到if当中
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }

        //判断命中的值是否为空值（穿透）
        if(Json != null) {
            // 返回错误信息
            return null;
        }

        //4.实现缓存重建

        //4.1 获取互斥锁
        String lockKey = lockPrefix + id;
        T t = null;
        try {
            boolean isLock = tryLock(lockKey);

            //4.2 判断是否获取成功
            if(!isLock) {
                //4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockPrefix, id, type, dbFallBack, time, unit);
            }

            //4.4 成功，根据id查询数据库
            t = dbFallBack.apply(id);
            // 模拟重建延时
            Thread.sleep(200);

            //5.数据库不存在，返回错误（解决缓存穿透）
            if(t == null) {
                //将空值写入Redis
                this.setWithLogicalExpire(key, null, time, unit);

                return null;
            }

            //6.存在，写入Redis并设置超时时间
            this.set(key, t, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }

        //8.返回
        return t;
    }


    /**
     * 尝试获取锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
