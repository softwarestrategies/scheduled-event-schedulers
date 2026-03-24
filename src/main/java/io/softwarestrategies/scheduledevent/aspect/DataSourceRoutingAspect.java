package io.softwarestrategies.scheduledevent.aspect;

import io.softwarestrategies.scheduledevent.config.DataSourceContextHolder;
import io.softwarestrategies.scheduledevent.config.DataSourceType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(-1) // Ensure it runs before standard @Transactional
public class DataSourceRoutingAspect {

    @Around("@annotation(io.softwarestrategies.scheduledevent.annotation.ReadOnlyRoute)")
    public Object routeToReplica(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.clearDataSourceType();
        }
    }
}
