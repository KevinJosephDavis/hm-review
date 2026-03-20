-- 1.参数列表：
-- 1.1 要判断库存是否充足，应当知道优惠券id
local voucherId = ARGV[1]
-- 1.2 要判断用户是否下单，应当知道用户id
local userId = ARGV[2]

-- 2.有关数据key
-- 2.1 库存key，值为库存
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key，值为set集合，集合中存放购买过该优惠券的用户id
local orderKey = 'seckill:order:' .. voucherId

-- 3.业务
-- 3.1 判断库存是否充足，即get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
	-- 3.2 库存不足，返回1
	return 1
end

-- 3.3 判断用户是否下单，即sismember orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
	-- 3.4 用户已经下过单，返回2
	return 2
end

-- 3.5 扣减库存，即incrby stockKey -1
redis.call('incrby', stockKey, -1)

-- 3.6 下单（保存用户）
redis.call('sadd', orderKey, userId)

return 0