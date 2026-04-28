package com.king.nexus.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean
    public EmbeddingModel embeddingModel() {
        System.out.println(">>> 🧠 正在加载中文最强轻量级 Embedding 模型 (BGE-Small)...");
        return new BgeSmallZhV15EmbeddingModel();
    }
}
