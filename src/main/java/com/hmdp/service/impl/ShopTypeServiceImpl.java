package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */
    @Override
    public Result queryShopType() {

        String key = "cache:shop:type";

        //1.查询Redis中所有商铺类型
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断Redis中是否存在
        if(shopTypeJson != null) {
            //3.存在，直接返回
            return Result.ok(JSONUtil.toList(shopTypeJson, ShopType.class));
        }

        //4.不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //5.数据库中也不存在，返回错误
        if(typeList.isEmpty()) {
            return Result.fail("未找到商铺类型");
        }

        //6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));

        //7.返回
        return Result.ok(typeList);
    }
}
