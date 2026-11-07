-- KEYS[1]：锁的 key；ARGV[1]：当前线程的完整持有者标识
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end

-- 锁不存在或持有者不匹配时，不删除任何锁
return 0
