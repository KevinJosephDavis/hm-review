package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    //使用静态常量与静态代码块，因此类一加载脚本就初始化完成了，避免每次释放锁时加载，多了很多IO操作

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;//锁名字不写死
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        //set nx满足互斥性，ex保证故障时锁依然能释放，避免死锁
        //利用Redis集群保证高可用和高并发

        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //这里应该使用UUID，因为在集群模式下，每个JVM都会维护threadId这样一个递增的数字，因此线程Id可能会重复
        //可以在UUID后在跟上线程Id，两者结合成一个全局唯一Id

        return Boolean.TRUE.equals(success);//如果null，也返回false，避免NPE
    }

    @Override
    public void unlock() {

        /*
        //判断标识是否一致
        String threadId = ID_PREFIX + Thread.currentThread().getId() + name;

        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
         */

        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }
}
