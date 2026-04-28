package com.king.nexus;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 🎯 高维空间制导靶场 (Vector Math Test)
 * 作用：不连数据库，纯在内存里计算两句话的“语义距离”。
 */
@SpringBootTest // 启动 Spring 容器，因为我们需要注入你在 AiConfig 里配置的那个本地模型
public class VectorMathTest {

    @Autowired
    private EmbeddingModel embeddingModel; // 你的微型 AI 大脑

    @Test
    public void testCosineSimilarity() {
        System.out.println("====== 🚀 开启高维语义测距实验 ======");

        // 1. 模拟三张表在“字典”里的介绍 (这就是我们刚才存进库里的东西)
        String tableUsers = "Table: t_users. 包含买家、客户、男女、性别、地区、城市、注册信息。";
        String tableProducts = "Table: t_products. 包含产品、商品、品类、数码、零食、美妆、单价信息。";
        String tableOrders = "Table: t_orders. 包含订单、销售额、业绩、卖了多少钱、退款、支付、下单信息。";

        // 2. 模拟老板 (用户) 各种千奇百怪的问法
        // 你可以自己修改这里的话，看看 AI 能不能听懂！
        String userQuery = "帮我查一下上个月的业绩怎么样？";

        System.out.println(">>> 老板的问题是：[" + userQuery + "]");

        // 3. 把所有的“人话”，坍缩成 384 维的数学向量 (float[])
        float[] vQuery = embeddingModel.embed(userQuery).content().vector();
        float[] vUsers = embeddingModel.embed(tableUsers).content().vector();
        float[] vProducts = embeddingModel.embed(tableProducts).content().vector();
        float[] vOrders = embeddingModel.embed(tableOrders).content().vector();

        // 4. 计算老板的问题，和三张表的“余弦相似度”
        // 相似度越接近 1，说明这两句话的意思越像；越接近 0 (甚至负数)，说明风马牛不相及。
        double scoreUsers = cosineSimilarity(vQuery, vUsers);
        double scoreProducts = cosineSimilarity(vQuery, vProducts);
        double scoreOrders = cosineSimilarity(vQuery, vOrders);

        // 5. 公布打靶成绩
        System.out.println("\n>>> 📊 语义匹配得分 (满分 1.0) :");
        System.out.println("-> 匹配 [用户表] 的得分: " + String.format("%.4f", scoreUsers));
        System.out.println("-> 匹配 [产品表] 的得分: " + String.format("%.4f", scoreProducts));
        System.out.println("-> 匹配 [订单表] 的得分: " + String.format("%.4f", scoreOrders));

        System.out.println("\n====== 🏁 实验结束 ======");
    }

    // ---------------------------------------------------------
    // 附录：高中数学复习 - 余弦相似度计算公式 (A·B / (|A|*|B|))
    // 作用：计算两个多维向量夹角的余弦值。夹角越小(趋近于0度)，余弦值越接近 1。
    // ---------------------------------------------------------
    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0; // 防止除以 0
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
