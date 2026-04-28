package com.king.nexus.nodes.strategy;

import org.springframework.stereotype.Component;

@Component // 必须加这个，让 Spring 把它收编进部队
public class DataIntentStrategy implements IntentStrategy {

    @Override
    public boolean supports(String intent) {
        return "DATA".equalsIgnoreCase(intent);
    }

    @Override
    public String getNextNodeId() {
        return "node_sql_gen"; // 如果是查数据，去找翻译官
    }
}
