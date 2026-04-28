package com.king.nexus.listener;

import com.king.nexus.event.NodeExecutionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 👑 帝国暗影史官 (SysLogListener)
 * 作用：躲在暗处，竖起耳朵听天空中的信号弹 (NodeExecutionEvent)。
 * 听到一发，就偷偷在数据库的 sys_exec_log 表里刻上一笔。
 * 绝招：动作极快，且绝不挡道 (@Async 异步执行)。
 */
@Component
public class SysLogListener {

    // 史官的刻刀 (操作 MySQL)
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 核心监听方法
     *
     * @EventListener：这是史官的耳朵。只要有人 publishEvent 了这个类型的事件，他就能听到。
     * @Async：这是史官的轻功。听到事件后，他会【瞬间分身】去另一个线程干活，绝对不让喊话的将军等他刻完字。
     */
    @EventListener
    @Async // 【核弹级关键】：没有这个注解，写数据库就会卡住聊天流程！
    public void onNodeExecutionEvent(NodeExecutionEvent event) {

        // 刻石碑的 SQL (我们在计划二时建的表)
        String sql = "INSERT INTO sys_exec_log (trace_id, node_id, content) VALUES (?, ?, ?)";

        try {
            // 开始刻字
            jdbcTemplate.update(sql, event.getTraceId(), event.getNodeId(), event.getMessage());

            // 史官自己嘟囔一句 (用来给你调试看效果的，真实环境可以删掉)
            System.out.println(">>> [暗影史官] 已将信号弹刻入石碑: [" + event.getNodeId() + "] " + event.getMessage());

        } catch (Exception e) {
            // 如果刻刀断了 (数据库挂了)，史官自己咽下这口气，绝对不去打扰前线打仗的将军们。
            System.err.println(">>> [暗影史官] 刻碑失败，但绝不影响前线: " + e.getMessage());
        }
    }
}
