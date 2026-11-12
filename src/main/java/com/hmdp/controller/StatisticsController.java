package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IStatisticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;

/**
 * 第 12 章：使用正常业务接口演示 PFADD / PFCOUNT，不灌入测试数据。
 */
@RestController
@RequestMapping("/statistics/uv")
public class StatisticsController {

    @Resource
    private IStatisticsService statisticsService;

    @PostMapping("/visit")
    public Result recordVisit(@RequestParam(value = "visitorId", required = false) String visitorId) {
        return statisticsService.recordVisit(visitorId);
    }

    @GetMapping
    public Result queryUv(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return statisticsService.queryUv(date);
    }
}
