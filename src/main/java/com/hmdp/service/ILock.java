package com.hmdp.service;

public interface ILock {

    /**
     * @prama timeoutSec锁持有时间
     * */
    boolean tryLock(Long timeoutSec);
    /**
    * 释放锁
    * */
    void unlock();
}
