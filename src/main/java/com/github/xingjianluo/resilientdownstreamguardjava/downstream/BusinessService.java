package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContext;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 业务入口：为每次调用设置请求上下文（未显式给定时生成 requestId），
 * 下游的熔断/隔离参数由 {@link DownstreamGuard} 依据 GuardProperties 懒注册。
 */
@Service
public class BusinessService {

    private final DownstreamGuard guard;
    private final Map<DownstreamName, DownstreamService> downstreams;

    public BusinessService(DownstreamGuard guard, Map<DownstreamName, DownstreamService> downstreams) {
        this.guard = guard;
        this.downstreams = downstreams;
    }

    /** 以显式上下文发起调用（测试用）。 */
    public GuardResult callWithContext(RequestContext context, DownstreamName downstream,
                                       String businessKey, long timeoutMillis) {
        RequestContext previous = RequestContextHolder.get();
        RequestContextHolder.set(context);
        try {
            return doCall(downstream, businessKey, timeoutMillis);
        } finally {
            if (previous != null) {
                RequestContextHolder.set(previous);
            } else {
                RequestContextHolder.clear();
            }
        }
    }

    /**
     * @param downstream 目标下游
     * @param businessKey 业务幂等键
     * @param timeoutMillis 超时；&lt;=0 时使用守卫默认超时
     */
    public GuardResult call(DownstreamName downstream, String businessKey, long timeoutMillis) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null) {
            ctx = new RequestContext(UUID.randomUUID().toString(), businessKey, Map.of());
            RequestContextHolder.set(ctx);
            try {
                return doCall(downstream, businessKey, timeoutMillis);
            } finally {
                RequestContextHolder.clear();
            }
        }
        return doCall(downstream, businessKey, timeoutMillis);
    }

    private GuardResult doCall(DownstreamName downstream, String businessKey, long timeoutMillis) {
        DownstreamService service = downstreams.get(downstream);
        return guard.execute(downstream, businessKey, timeoutMillis,
                () -> service.invoke(businessKey));
    }
}
