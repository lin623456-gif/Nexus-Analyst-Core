package com.king.nexus.controller;

import com.pgvector.PGvector;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class VectorInitController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EmbeddingModel embeddingModel;

    @GetMapping("/init-vectors")
    public String initVectors() {
        System.out.println(">>> 🚀 开始为高维兵器库装填弹药...");
        jdbcTemplate.execute("TRUNCATE TABLE meta_tables_v2 RESTART IDENTITY");

        String userTable = "Table: t_users\nColumns: id(int), username(varchar), gender(varchar), city(varchar), reg_date(datetime)\nComment: 包含买家、客户、男女、性别、地区、城市、注册信息。";
        String productTable = "Table: t_products\nColumns: id(int), product_name(varchar), category(varchar), price(decimal)\nComment: 包含产品、商品、品类、数码、零食、美妆、单价信息。";
        String orderTable = "Table: t_orders\nColumns: id(int), user_id(int), product_id(int), total_amount(decimal), order_status(varchar), create_time(datetime)\nComment: 包含订单、销售额、业绩、卖了多少钱、退款、支付、下单信息。注意外键：t_orders.user_id=t_users.id, t_orders.product_id=t_products.id";

        float[] userVector = embeddingModel.embed(userTable).content().vector();
        float[] productVector = embeddingModel.embed(productTable).content().vector();
        float[] orderVector = embeddingModel.embed(orderTable).content().vector();

        String sql = "INSERT INTO meta_tables_v2 (table_name, ddl_content, embedding) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, "t_users", userTable, new PGvector(userVector));
        jdbcTemplate.update(sql, "t_products", productTable, new PGvector(productVector));
        jdbcTemplate.update(sql, "t_orders", orderTable, new PGvector(orderVector));

        return "SUCCESS: 弹药装填完毕，武器已上膛！";
    }
}
