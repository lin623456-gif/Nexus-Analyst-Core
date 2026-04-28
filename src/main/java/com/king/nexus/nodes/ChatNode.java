package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 👑 帝国首席陪聊官 (ChatNode)
 * 作用：当老板(用户)不想查数据，只想闲聊、问候、或者问以前说过的话时，由他来接待。
 * 绝招：记性极好。因为他会带着从地下室(Redis)搬出来的整个档案袋(上下文记忆)，去找大模型聊天。
 */
@Component
public class ChatNode implements Node {

    // 陪聊官没有自己的脑子，他只会把老板的话传给中央大脑 (LlmService)
    @Autowired
    private LlmService llmService;

    @Override
    public String getId() {
        // 这是他在花名册上的唯一代号，RouterNode 分配任务时就是喊这个名字
        return "node_chat";
    }

    @Override
    public String execute(NodeContext ctx) {
        ctx.addLog("ChatNode: 陪聊官就位，准备进入闲聊模式...");

        // ==========================================
        // 核心动作：【带记忆的聊天】(Contextual Chat)
        // 婴儿级解释：
        // 如果这里只传 ctx.getOriginalQuery()，大模型就会像个金鱼，聊完这句忘上句。
        // 所以，我们必须把 ctx.getHistoryList() (从 Redis 里捞出来的前世记忆) 也传进去！
        // 这样大模型就能联系上下文，回答“那我刚才问的是什么水果？”这种问题了。
        // ==========================================

        ctx.addLog(">>> 陪聊官正在打包老板的最新问题，并附上 " +
                (ctx.getHistoryList() != null ? ctx.getHistoryList().size() : 0) +
                " 条历史记忆，发往中央大脑...");

        // 调用 LlmService 的重载方法 (带两个参数的那个)
        String response = llmService.chat(ctx.getOriginalQuery(), ctx.getHistoryList());

        // ==========================================
        // 收尾动作：【记录答案】
        // 婴儿级解释：把大模型说的话，记在档案袋的 finalAnswer 里。
        // 引擎 (GraphEngine) 看到这个 finalAnswer 后，就知道活干完了，会自动把它存进 Redis 里。
        // ==========================================
        ctx.setFinalAnswer(response);

        ctx.addLog("✅ 陪聊官接待完毕，神明的回复已装袋。");

        // 陪聊官也是流水线的终点站，聊完天就没事了，返回 null 结束整个流程。
        return null;
    }
}