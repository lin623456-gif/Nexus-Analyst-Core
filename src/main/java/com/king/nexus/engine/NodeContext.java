package com.king.nexus.engine;

import com.king.nexus.event.NodeExecutionEvent;
import lombok.Data;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Data
public class NodeContext {

    // ==========================================
    // 1. 基础身份与数据装载区
    // ==========================================
    private String traceId;          // 新增：防伪追踪码 (每次提问生成一个唯一的 ID)
    private String currentNodeId;    // 新增：当前公文包在哪个将军手里 (用来写日志)
    private String userId;
    private String redisKey;

    private String originalQuery;
    private String generatedSql;
    private List<Map<String, Object>> queryResult;
    private String lastError;
    private int retryCount = 0;
    private String finalAnswer;

    private List<String> logs = new ArrayList<>();
    private List<String> historyList;

    // ==========================================
    // 2. 通信兵专区 (对外联络工具)
    // ==========================================

    // 工具 A：老板的专线电话 (用于前端 SSE 文字直播)
    private Consumer<String> sseEmitterCallback;

    // 工具 B：帝国的信号枪 (用于触发后台默默写数据库日志的 Event)
    private ApplicationEventPublisher eventPublisher;

    // ==========================================
    // 3. 核心发声方法 (一语双关)
    // ==========================================
    public void addLog(String message) {
        // 1. 记在自己的小本本上 (控制台打印)
        this.logs.add(message);
        System.out.println("\033[34;1m【Nexus核心思考】\033[0m: " + message);

        // 2. 抄送给老板 (如果老板在线的话)
        if (this.sseEmitterCallback != null) {
            try {
                this.sseEmitterCallback.accept(message);
            } catch (Exception e) {
                System.err.println(">>> [通信兵] 老板电话打不通 (前端已断开)。");
            }
        }

        // 3. 抄送给史官 (如果带了信号枪，就往天上打一发)
        // 婴儿级解释：这一枪打出去，后台的 SysLogListener 就会听到，然后偷偷把这句话记进 MySQL 数据库。
        if (this.eventPublisher != null && this.traceId != null) {
            // 打包一个军情急报 (包含追踪号、哪个将军说的、说了什么)
            NodeExecutionEvent event = new NodeExecutionEvent(
                    this,
                    this.traceId,
                    this.currentNodeId != null ? this.currentNodeId : "system",
                    message
            );
            // 砰！发射！
            this.eventPublisher.publishEvent(event);
        }
    }
}