package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //如果一个线程尝试从阻塞队列（空）中获取元素，那么它就会阻塞，直到队列中有元素可用，才会被唤醒
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //当前类初始化完毕后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    //1.获取队列中的订单信息（阻塞）
                    VoucherOrder voucherOrder = orderTasks.take();

                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        //1.获取用户
        //这里不能再用从UserHolder取线程id了，因为这里是线程池的一个全新的线程，所以从ThreadLocal里是取不到的
        Long userId = voucherOrder.getUserId();

        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //3.获取锁
        boolean isLock = lock.tryLock();

        //4.判断是否成功获取锁
        if(!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }

        try {
            //这里无法获取代理对象，因为其底层也是通过ThreadLocal获取的，所以我们要在主线程里获取
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();

        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId
        );

        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            //3.如果不是，返回异常信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //4.如果是，把下单信息保存到阻塞队列中
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //4.1 订单Id
        voucherOrder.setId(orderId);

        //4.2 用户Id
        voucherOrder.setUserId(userId);

        //4.3 优惠券Id
        voucherOrder.setVoucherId(voucherId);

        //4.4 加入阻塞队列
        orderTasks.add(voucherOrder);

        //4.5 获取代理对象
        //可以传参，也可以放到成员变量中
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //5.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 创建订单
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();

        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        //5.2 判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            log.error("库存不足");
            return;
        }

        //7.创建订单
        save(voucherOrder);

        //不需要返回id，因为是异步执行
    }

    /**
     * 创建订单（旧版本）
     */
    @Transactional
    public Result createVoucherOrderOld(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();

        /*
        1.如果直接将synchronized加在方法上，那么锁对象是this，即当前VoucherOrderServiceImpl实例
        Spring Bean默认是单例的，全局唯一，所有用户共享一个实例
        其影响范围就是整个服务类的所有调用，且它们使用的都是同一把锁，即使操作的是不同用户，导致所有请求串行执行
        而一人一单只需要保证同一个用户的线程安全就可以了，因此加在方法上不合适
        2.如果synchronized(userId)，由于Long类型不是单例，因此如果其值不在[-128,127]之间，那么底层就会new一个新的Long对象
        -128-127这256个Long对象永远存在，不会被GC回收
        而用户Id通常大于127，因此无法保证锁的是同一个对象
        3.如果synchronized(userId.toString())，那么由于toString底层是new一个字符串
        因此哪怕值一样，对象也是不同的，因此每个线程都有自己的锁，锁粒度太细，相当于没有锁

        所以使用intern()，它从字符串常量池获取唯一引用，这样只要userId一样，其锁也是一样的
        字符串常量池不像Long缓存不同，它没有固定范围。
        intern先检查常量池中是否存在该字符串，如果存在则返回池中的引用，如果不存在则将当前字符串加入池中返回引用
        这就实现了同一个用户的多个线程串行，不同用户的请求并行
        */
        /*
        但是又有新问题，当synchronized块结束之后，锁被释放，但@Transactional注解是加在方法上的
        如果在事务提交到数据库之前，其它线程拿到了锁，它还是会去查询订单，会发现用户可能没下过单，进而去创建订单，违反了一人一单原则
        因此synchronized应该加在上面的return里
        */
        //synchronized(userId.toString().intern()) {//

        //在多线程情况下，如果一开始count为0，那么这些线程都会得出结论：该用户没有下过单，因此都会去创建订单
        //由于这里不涉及修改的问题，是新增了订单，所以使用悲观锁synchronized
            /*
            //5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            //5.2 判断是否存在
            if(count > 0) {
                //用户已经购买过
                return Result.fail("用户已经购买过一次");
            }
            */

        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        //5.2 判断是否存在
        if (count > 0) {
            //用户已经购买过
            return Result.fail("用户已经购买过一次");
        }


        //6.扣减库存
            /* boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .eq("stock", voucher.getStock())
                .update();*/
        //等价于 UPDATE seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? and stock = ?
        //用户先查询库存，然后判断库存是否足够，如果where条件里的stock不为原来查到的库存数，那么就不会扣减库存，因为有人抢购了
        //但是乐观锁的问题在于，失败率会大大增加
        //假设有100个库存，同时有100个线程来抢，一开始每一个线程查到的库存都是100
        //但是，只有一个线程真正去数据库更新了
        //更新之后，剩下的99个线程发现where语句的条件都不符合，所以会报错。但在业务上，库存还剩下99
        //改进：查询条件没必要那么苛刻，不一定拿到的stock与查询时拿到的stock一致，只要大于0就可以
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        //等价于 UPDATE seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? and stock > 0

        if (!success) {
            return Result.fail("库存不足");
        }

        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        //7.1 订单Id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //7.2 用户Id
        voucherOrder.setUserId(userId);

        //7.3 优惠券Id
        voucherOrder.setVoucherId(voucherId);

        //8.将订单存入数据库
        save(voucherOrder);

        return Result.ok(orderId);
        //}
    }

    public Result seckillVoucherOld(Long voucherId) {

        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }

        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        //4.判断库存是否充足
        if(voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        /*
        超卖问题：线程1查询库存，发现库存只剩1个，在线程1判断库存是否大于0之前，线程2也查询库存，由于线程1还没来得及扣减库存，因此线程2会认为还有库存，从而导致超卖
        解决方案是使用乐观锁：只在更新数据时去判断有没有其它线程对数据做出修改。常见方法有版本号法（每做一次修改，版本号就+1）与CAS
         */

        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()) {
//            //9.返回订单Id
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        //事务提交后再释放锁
        /*
        但需要注意的是事务范围问题，我们只在创建订单方法上加了事务，没有给seckillVoucher方法加事务
        return 语句实际上是 return this.createVoucherOrder(voucherId);
        this实际上是VoucherOrderServiceImpl实例，而不是它的代理对象
        事务要想生效，是Spring对当前对象做了动态代理，拿到了它的代理对象，用代理对象做事务处理
        所以这里会事务失效，我们应该要拿到事务的代理对象
        还要在.xml中加入依赖：
        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjweaver</artifactId>
        </dependency>
        以及在启动类中添加EnableAspectJAutoProxy(exposeProxy = true)，用来暴露代理对象，暴露了才能拿到代理对象
         */

        //为了解决集群下线程并发安全问题，我们手动创建锁
        //在JVM内部有一个锁监视器，我们通过userId.toString.intern()这个在字符串常量池里的对象来标记锁的持有者
        //而在集群模式下，有多套JVM，不同的JVM有不同的堆栈、常量池，那么就会有多个线程获取到锁，就又有并发问题了
        //要想办法让多个JVM使用同一把锁，实现进程互斥而不仅仅是线程互斥、多进程可见（JVM外部，如MySQL、Redis），那么我们就需要使用分布式锁

        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        //boolean isLock = lock.tryLock(1200);
        //如果不传参，默认waitTime为-1，即不等待，默认释放时间为30秒
        boolean isLock = lock.tryLock();

        //判断是否成功获取锁，如果没有成功，不阻塞
        if(!isLock) {
            return Result.fail("同一用户不能重复下单");
        }

        try {
            //成功获取锁
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrderOld(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

        /*
        SimpleRedisLock的问题：
        1.误删问题： 1   获取到锁的线程出现了业务阻塞，业务逻辑还没搞定，就触发超时释放锁
        这个释放是Redis层面的释放，并不是它调用了unlock
        也就是说，当这个线程执行完业务逻辑后，它还会调用unlock
        但此时它并不是锁的持有者，锁的持有者是其它线程
        但它却误删了其它线程持有的锁，这个被误删锁的线程是不知道的
        因此此时可能会有另一个线程趁虚而入获取到了锁，那么又出现了并行的情况了
        解决方法：释放锁之前，获取锁标识并判断是否一致

        2.原子性问题：判断锁标识和自己的标识一致，准备去释放锁
        但在即将去释放锁的路上，发生了阻塞（JVM Full GC）
        如果阻塞时间超过了我们规定的锁持有时间，那么又会出现问题一的情况
        因为这个即将释放锁的线程它已经判断过了，它以为锁还是自己的，所以它会直接释放
        其本质原因：判断锁标识与释放锁是两步动作
        解决方法：保证这两步操作的原子性
        使用Lua脚本，在一个脚本中编写多条Redis命令，确保多条命令执行时的原子性
        因为Redis是单线程执行命令的，而Lua脚本在Redis中执行时，整个脚本会被当作一个单一的Redis命令来执行
        在Lua脚本执行期间，Redis不会插入执行其他任何命令

        3.但是现在又有新问题：
        （1）不可重入：同一个线程无法多次获取同一把锁
        例：方法1和方法2都有获取锁的动作，方法1获取锁之后调用方法2，那么方法2获取锁必然失败
        解决：获取锁和释放锁之前检查当前线程是否持有锁，维护一个计数器，记录重用次数
        如何知道什么时候才要释放锁呢？当重用次数为0时，就代表一定走到了方法的最外层
        可见，redis中的存储结构应该是key:lock，value:{field:thread1,value:0}这样的哈希结构
        但是哈希结构是没有SET NX EX的
        所以流程改为如下：开始时，判断锁是否存在，如果不存在，获取锁并添加线程标识，设置锁有效期，执行业务；
        如果存在，判断锁标识是否是自己，如果不是就获取锁失败，如果是就将锁计数+1，设置锁有效期（因为锁在上一层方法中已经消耗了一定时间），执行业务；
        释放前，判断锁是否是自己的，如果不是，不用管；如果是，将锁计数-1，判断锁计数是否为0
        如果不是，就重置锁有效期，继续执行业务；如果是，就释放锁

        这么多操作，显然必须要用Lua脚本来保证原子性
        （2）不可重试：获取锁只尝试一次就返回false，没有重试机制
        （3）超时释放：业务执行耗时较长也会导致锁释放
        （4）主从一致性
        直接使用Redisson
         */

        /*
        秒杀业务的流程：查询优惠券、判断秒杀库存、查询订单、校验一人一单、减库存、创建订单
        这些步骤都是串行执行的，其中查询优惠券、查询订单、减库存、创建订单都是数据库层面的操作，数据库的并发能力是较差的
        此外我们还加了分布式锁，进一步拉低了并发能力
        这相当于是一家餐馆的老板独自完成接待顾客、准备食材、炒菜这一整套流程，那么他在单位时间内能接待的顾客就少了
        所以我们要请不同的人来做不同的事情
        优化：将判断秒杀库存与校验一人一单放在redis当中，保存优惠券id、用户id、订单id到阻塞队列中，相当于小票，记录谁买了什么。返回一个订单id给用户
        如果用户有下单资格，开启独立线程异步读取队列信息，相当于厨师拿到小票，就能根据用户点的菜进行烹饪
        用户可以通过订单id付款
        所以相当于将业务缩短为：客户端 -> nginx -> redis（判断是否有资格） -> 返回结果给用户

        我们要在redis中判断库存是否充足与一人一单，需要将有关库存信息与订单信息缓存到redis当中
        选择数据结构：库存：{key:stock:voucherId,value:stock}
        一人一单：利用set集合中元素的唯一性（用户id要唯一才遵循一人一单原则）
        {key:order:voucherId,value:userId}
        流程：判断库存是否充足？
        是：判断用户是否下单过？
            如果是，就返回2
            如果否，就扣减库存并将userId存入当前优惠券的set集合，返回0
        否：返回1
        由于流程很多，要确保原子性，因此要使用Lua脚本
        因此，在Java程序中，流程为：执行Lua脚本，判断结果是否为0？
        否：返回异常信息，结束
        是：将优惠券id、用户id、订单id存入阻塞队列，返回订单id

        总结：
        1.新增秒杀优惠券的同时，将优惠券信息保存到Redis当中
        2.基于Lua脚本，判断秒杀库存、一人一单，决定用户是否购买成功
        3.如果购买成功，将优惠券id、用户id和订单id封装后存入阻塞队列
        4.开启线程任务，不断从阻塞队列中获取信息，实现异步下单功能
         */
    }
}
