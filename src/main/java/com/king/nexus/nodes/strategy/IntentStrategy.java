package com.king.nexus.nodes.strategy;

/**
 * 👑 帝国意图处理军规 (Intent Strategy)
 */
public interface IntentStrategy {

    /**
     * 第一条军规：你能处理什么意图？
     * @param intent 大模型解析出来的意图，比如 "DATA" 或 "CHAT"
     * @return 如果归你管，返回 true；否则返回 false
     */
    boolean supports(String intent);

    /**
     * 第二条军规：如果归你管，下一步公文包该传给哪个将军？
     * @return 下一个 Node 的 ID，比如 "node_sql_gen"
     */
    String getNextNodeId();
}
