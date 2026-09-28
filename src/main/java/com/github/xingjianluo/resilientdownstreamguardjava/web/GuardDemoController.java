package com.github.xingjianluo.resilientdownstreamguardjava.web;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地验证用演示接口（骨架）：触发受保护调用、查询指标与熔断状态。
 */
@RestController
public class GuardDemoController {

    public GuardDemoController(DownstreamGuard guard) {
    }
}
