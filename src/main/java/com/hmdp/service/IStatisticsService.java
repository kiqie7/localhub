package com.hmdp.service;

import com.hmdp.dto.Result;

import java.time.LocalDate;

/**
 * 基于 HyperLogLog 的按日独立访客统计。
 */
public interface IStatisticsService {

    Result recordVisit(String visitorId);

    Result queryUv(LocalDate date);
}
