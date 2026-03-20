package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建订单
     */
    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 创建订单（旧版本）
     */
    Result createVoucherOrderOld(Long voucherId);
}
