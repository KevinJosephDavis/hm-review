package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;//锁名字不写死
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        //获取锁
        String key = KEY_PREFIX + name;
        long threadId = Thread.currentThread().getId() ;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId + "", timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);//如果null，也返回false，避免NPE
    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
