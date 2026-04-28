package com.king.nexus.domain;

import lombok.Data;

/**
 * 👑 帝国模具厂：大模型输出强约束容器 (Structured CoT Response)
 *
 * 作用：这是用来“框死”大模型的模具。大模型生成的文本，必须能完美地倒进这三个格子里。
 * 如果倒不进去（比如它多说了两句废话破坏了 JSON 结构），解析就会报错，从而触发重试。
 */
@Data // 让 Lombok 帮我们自动生成 get/set 方法，保持代码干净
public class SqlGenResponse {

    /**
     * 【格子一：草稿纸 / 思考过程】
     * 婴儿级解释：逼着大模型在这里写“解题思路”。
     * 比如：“用户要查销量，我需要用到 t_orders 表，并且加上状态过滤...”
     * 作用：强迫它集中注意力，减少幻觉。这个过程我们还能打印在控制台上，让老板看着很爽。
     */
    private String thoughtProcess;

    /**
     * 【格子二：安全探针】
     * 婴儿级解释：让大模型自己当一回保安。
     * 如果它发现自己写的 SQL 里有 DELETE、DROP、UPDATE 这种毁灭性的词，它必须把这里填成 false。
     * 如果是普通的 SELECT 查询，填 true。
     * 作用：我们自己的系统只要看到 false，直接拉闸，绝对不把枪交给行刑官。
     */
    private boolean Safe;

    /**
     * 【格子三：真正的子弹】
     * 婴儿级解释：剥去了所有伪装、废话之后，最纯粹的那一行 SQL 语句。
     * 比如："SELECT * FROM t_orders"
     * 作用：这才是我们要塞进公文包，真正交给行刑官去执行的东西。
     */
    private String finalSql;

}
