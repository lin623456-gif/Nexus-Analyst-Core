package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 泛闲聊意图处理节点 (ChatNode)
 *
 * <p>路由定位：当请求经过意图识别判定为非数据查询类自然语言交互（如寒暄、记忆回溯、多轮上下文补充提问）时生效。
 * <p>核心能力：基于携带状态补偿的上下文编排机制，将当前输入与从缓存层加载的历史会话记录合并为上下文窗口，
 * 提交至大语言模型 (LLM) 进行连贯推理与自然语言生成 (NLG)。
 * <p>生命周期：作为工作流图 (Graph) 的末端叶子节点，输出终态回答后将执行权归还给核心调度引擎，触发会话归档流程。
 */
@Component
public class ChatNode implements Node {

    // 大模型推理服务接入组件，负责底层 Prompt 组装与 LLM 的远程调用
    @Autowired
    private LlmService llmService;

    /**
     * 获取服务节点在拓扑图中的唯一寻址标识。
     *
     * @return 注册标识符 "node_chat"，供上层路由分发器在意图识别后进行下游节点挂载。
     */
    @Override
    public String getId() {
        return "node_chat";
    }

    /**
     * 执行泛闲聊意图的上下文编排与推理逻辑。
     *
     * <p>处理流程：
     * <ol>
     *   <li>将当前用户输入与从分布式缓存加载的历史对话列表合并，构建多轮对话上下文窗口。</li>
     *   <li>调用 {@link LlmService} 进行基于 Prompt Engineering 的推理，该过程可能涉及长下文拼接与注意力对齐。</li>
     *   <li>将 LLM 返回的终结态回答写入 {@link NodeContext#finalAnswer}，并释放控制权给调度引擎以触发异步历史状态持久化。</li>
     * </ol>
     *
     * @param ctx 当前请求的完整执行上下文，承载全链路 Trace ID、历史会话、流式回调等元数据
     * @return null，标识当前节点为工作流终点，不再触发后续节点的上下文流转
     */
    @Override
    public String execute(NodeContext ctx) {
        ctx.addLog("ChatNode: 陪聊官就位，准备进入闲聊模式...");

        // ==========================================
        // 上下文装配：基于多轮会话状态的 Prompt 注入
        // 将当前输入与从 Redis 获取的 HistoryList 进行序列化合并，解决上下文遗忘问题
        // 以此支撑需要指代消解或记忆依赖的连续对话语义理解
        // ==========================================

        ctx.addLog(">>> 陪聊官正在打包老板的最新问题，并附上 " +
                (ctx.getHistoryList() != null ? ctx.getHistoryList().size() : 0) +
                " 条历史记忆，发往中央大脑...");

        // 调用大模型推理服务，执行深度语义计算与自然语言生成 (NLG)
        String response = llmService.chat(ctx.getOriginalQuery(), ctx.getHistoryList());

        // ==========================================
        // 终态结果回填：将生成回答挂载至上下文载体
        // 调度引擎后续将基于此 finalAnswer 触发异步归档与 SSE 响应终结帧下发
        // ==========================================
        ctx.setFinalAnswer(response);

        ctx.addLog("✅ 陪聊官接待完毕，神明的回复已装袋。");

        // 返回 null 终止图调度，标示当前路由分支已完成全链路处理
        return null;
    }
}
