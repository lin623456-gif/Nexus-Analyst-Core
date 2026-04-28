import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ExportAllCode {

    // 输出文件名
    private static final String OUTPUT_FILE = "all_project_code.txt";

    public static void main(String[] args) throws IOException {
        // 1. 找到源代码目录
        File srcDir = new File("src/main/java");
        if (!srcDir.exists()) {
            System.err.println("❌ 找不到 src/main/java 目录！请确保脚本在项目根目录下运行。");
            return;
        }

        // 2. 递归扫描所有 .java 文件
        List<File> javaFiles = new ArrayList<>();
        scanFiles(srcDir, javaFiles);

        // 3. 写入输出文件
        try (FileWriter writer = new FileWriter(OUTPUT_FILE)) {
            writer.write("====== Nexus-Analyst 项目代码汇总 ======\n\n");

            for (File file : javaFiles) {
                // 打印分隔符和文件名
                writer.write("--------------------------------------------------\n");
                writer.write("📄 文件: " + file.getPath() + "\n");
                writer.write("--------------------------------------------------\n");

                // 读取文件内容
                String content = Files.readString(file.toPath());
                writer.write(content);
                writer.write("\n\n");
            }
        }

        System.out.println("✅ 导出完成！共导出 " + javaFiles.size() + " 个文件。");
        System.out.println("📂 结果保存在: " + new File(OUTPUT_FILE).getAbsolutePath());
    }

    private static void scanFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanFiles(file, result); // 递归
            } else if (file.getName().endsWith(".java")) {
                result.add(file); // 只要 .java
            }
        }
    }
}
