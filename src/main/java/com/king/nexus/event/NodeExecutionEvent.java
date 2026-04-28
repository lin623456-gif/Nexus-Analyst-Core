package com.king.nexus.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 👑 帝国军情急报 (NodeExecutionEvent)
 */
@Getter // 【优雅】：让 Lombok 帮我们自动生成 getTraceId 等方法
public class NodeExecutionEvent extends ApplicationEvent {

    private final String traceId;
    private final String nodeId;
    private final String message;

    public NodeExecutionEvent(Object source, String traceId, String nodeId, String message) {
        super(source);
        this.traceId = traceId;
        this.nodeId = nodeId;
        this.message = message;
    }
}
