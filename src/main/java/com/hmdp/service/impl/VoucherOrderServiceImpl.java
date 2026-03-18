package com.hmdp.service.impl;

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
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀优惠券
     */
    @Override

    public Result seckillVoucher(Long voucherId) {

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

        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //获取锁
        boolean isLock = lock.tryLock(1200);

        //判断是否成功获取锁，如果没有成功，不阻塞
        if(!isLock) {
            return Result.fail("同一用户不能重复下单");
        }

        try {
            //成功获取锁
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

        /*
        SimpleRedisLock的问题：
        1.误删问题：获取到锁的线程出现了业务阻塞，业务逻辑还没搞定，就触发超时释放锁
        这个释放是Redis层面的释放，并不是它调用了unlock
        也就是说，当这个线程执行完业务逻辑后，它还会调用unlock
        但此时它并不是锁的持有者，锁的持有者是其它线程
        但它却误删了其它线程持有的锁，这个被误删锁的线程是不知道的
        因此此时可能会有另一个线程趁虚而入获取到了锁，那么又出现了并行的情况了
        解决方法：释放锁之前，获取锁标识并判断是否一致

        2.原子性问题：判断锁标识和自己的标识一致，准备去释放锁
        但在即将去释放锁的路上，发生了阻塞（JVM）
        如果阻塞时间超过了我们规定的锁持有时间，那么又会出现问题一的情况
        因为这个即将释放锁的线程它已经判断过了，它以为锁还是自己的，所以它会直接释放
        其本质原因：判断锁标识与释放锁是两步动作
        解决方法：保证这两步操作的原子性
        说到原子性，自然想到事务
        使用Lua脚本，在一个脚本中编写多条Redis命令，确保多条命令执行时的原子性
        因为Redis是单线程执行命令的，而Lua脚本在Redis中执行时，整个脚本会被当作一个单一的Redis命令来执行
        在Lua脚本执行期间，Redis不会插入执行其他任何命令

        3.但是现在又有新问题：
        （1）不可重入：同一个线程无法多次获取同一把锁
        （2）不可重试：获取锁只尝试一次就返回false，没有重试机制
        （3）超时释放：业务执行耗时较长也会导致锁释放
        （4）主从一致性
        直接使用Redisson
         */
    }

    /**
     * 创建订单
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
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
}
