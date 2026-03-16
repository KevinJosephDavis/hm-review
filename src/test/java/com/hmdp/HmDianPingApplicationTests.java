package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown(); //任务完成，计数器减一
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();//等待任务完成，因为前面只是进行了提交
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        //让它尽快过期
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void testLongCache() {
        Long a1 = 100L;
        Long a2 = 100L;
        System.out.println(a1 == a2);

        Long b1 = 200L;
        Long b2 = 200L;
        System.out.println(b1 == b2);
    }

    @Test
    void testStringCache() {
        String s1 = new String("hello");  // 堆中创建新对象
        String s2 = s1.intern();          // 将 "hello" 加入常量池
        String s3 = "hello";              // 直接从常量池获取
        System.out.println(s1 == s2);
        System.out.println(s2 == s3);
    }
}
