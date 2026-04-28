package com.king.nexus.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 👑 帝国中央大脑 (LlmService)
 * 作用：唯一能和外部“神明”（大语言模型 API，如 DeepSeek/GPT）直接对话的祭司。
 * 绝招：能把人类的散装语言和过去的记忆打包，翻译成神明能听懂的格式 (ChatMessage)。
 */
@Service
public class LlmService {

    // 这是连接神明的通讯器 (由 Spring 自动根据你的 application.properties 配置好并注入)
    private final ChatLanguageModel chatModel;

    public LlmService(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
        System.out.println(">>> 👑 帝国中央大脑 (LLM) 已连接，随时待命。");
    }

    // ==========================================
    // 核心技能 1：【带记忆的深度对话】(Chat with History)
    // 婴儿级解释：不仅把老板这次问的问题发给大模型，还把之前聊过的 20 句话也一起发过去。
    // 这样大模型就不会像个傻子一样问“你刚才说什么？”了。
    // ==========================================
    public String chat(String currentPrompt, List<String> historyList) {

        System.out.println("\n====== [中央大脑] 正在向神明发送祈祷 ======");

        // 1. 准备一个篮子 (List<ChatMessage>)，用来装我们要说的话。
        // 大模型不认识 String，它只认识 ChatMessage (分角色的消息)。
        List<ChatMessage> messages = new ArrayList<>();

        // 2. 设定大模型的“人设” (SystemMessage)
        // 告诉它：“你是个聪明的助手，别乱说话。”
        messages.add(SystemMessage.from("你是一个全能的智能数据助手，名为 Nexus。请根据上下文，简明扼要地回答用户的问题。"));

        // 3. 把地下室 (Redis) 搬来的旧档案 (历史记录)，翻译成大模型认识的格式，放进篮子。
        if (historyList != null && !historyList.isEmpty()) {
            for (String hist : historyList) {
                // 如果这句话是老板 (User) 说的
                if (hist.startsWith("User: ")) {
                    // 把 "User: " 前缀去掉，只留内容，装进篮子
                    messages.add(UserMessage.from(hist.substring(6)));
                }
                // 如果这句话是以前的 AI 说的
                else if (hist.startsWith("AI: ")) {
                    // 把 "AI: " 前缀去掉，装进篮子
                    messages.add(AiMessage.from(hist.substring(4)));
                }
            }
            System.out.println(">>> 🧠 大脑已成功注入 " + historyList.size() + " 条前世记忆！");
        } else {
            System.out.println(">>> 🧠 大脑发现这是个新客人，没有历史记忆。");
        }

        // 4. 把老板刚才最新的一句话，放在篮子的最上面。
        messages.add(UserMessage.from(currentPrompt));

        // 5. 把整个篮子献祭给神明 (调用大模型 API)
        // 注意：这里的 generate 接收的是 List<ChatMessage>，而不是 String。
        String response = chatModel.generate(messages).content().text();

        System.out.println("====== [中央大脑] 神明降下神谕 ======\n" + response);

        // 6. 把神明的话原样返回给调用它的将军们
        return response;
    }

    // ==========================================
    // 核心技能 2：【不带记忆的单句问答】(Chat without History)
    // 婴儿级解释：有些时候，我们不需要大模型知道历史。
    // 比如：让大模型翻译 SQL、或者分析意图。这种活儿，只要干完当前这句就行了。
    // 这是一个【重载方法】(Method Overloading)，名字也叫 chat，但是参数少一个。
    // ==========================================
    public String chat(String singlePrompt) {
        // 直接调用上面的核心技能 1，只不过把历史记录传 null。
        // 这叫代码复用，非常优雅。
        return chat(singlePrompt, null);
    }
}