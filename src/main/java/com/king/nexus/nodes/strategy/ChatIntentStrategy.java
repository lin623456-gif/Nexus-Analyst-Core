package com.king.nexus.nodes.strategy;

import org.springframework.stereotype.Component;

@Component
public class ChatIntentStrategy implements IntentStrategy {

    @Override
    public boolean supports(String intent) {
        return "CHAT".equalsIgnoreCase(intent);
    }

    @Override
    public String getNextNodeId() {
        return "node_chat"; // 如果是闲聊，去找陪聊官
    }
}
