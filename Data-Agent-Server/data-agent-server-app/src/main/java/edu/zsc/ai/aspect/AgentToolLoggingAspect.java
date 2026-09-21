package edu.zsc.ai.aspect;

import edu.zsc.ai.agent.tool.model.AgentToolResult;
import edu.zsc.ai.common.constant.AgentRuntimeLoggerNames;
import edu.zsc.ai.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order
@RequiredArgsConstructor
public class AgentToolLoggingAspect {

    private static final Logger runtimeLog = LoggerFactory.getLogger(AgentRuntimeLoggerNames.TOOL);

    @Around("@within(edu.zsc.ai.agent.annotation.AgentTool) || @annotation(edu.zsc.ai.agent.annotation.AgentTool)")
    public Object logToolInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        long startTime = System.currentTimeMillis();
        runtimeLog.info("tool_start class={} method={} arguments={}",
                signature.getDeclaringType().getSimpleName(),
                signature.getMethod().getName(),
                serialize(joinPoint.getArgs()));
        try {
            Object result = joinPoint.proceed();
            runtimeLog.info("tool_complete class={} method={} elapsedMs={} success={} result={}",
                    signature.getDeclaringType().getSimpleName(),
                    signature.getMethod().getName(),
                    System.currentTimeMillis() - startTime,
                    !(result instanceof AgentToolResult toolResult) || toolResult.isSuccess(),
                    serialize(result));
            return result;
        } catch (Throwable throwable) {
            runtimeLog.error("tool_error class={} method={} elapsedMs={} arguments={}",
                    signature.getDeclaringType().getSimpleName(),
                    signature.getMethod().getName(),
                    System.currentTimeMillis() - startTime,
                    serialize(joinPoint.getArgs()),
                    throwable);
            throw throwable;
        }
    }

    private String serialize(Object value) {
        try {
            return JsonUtil.object2json(value);
        } catch (RuntimeException ex) {
            return String.valueOf(value);
        }
    }
}
