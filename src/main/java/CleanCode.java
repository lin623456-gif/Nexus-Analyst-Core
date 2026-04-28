import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * 👑 帝国痕迹抹除器 (一键删除所有 Java 注释)
 * 作用：把带有“帝师”、“婴儿级”等中二注释的代码，洗成冷冰冰的工业级代码。
 */
public class CleanCode {

    // 1. 你的源代码目录 (如果你的项目不在这个路径，请修改)
    private static final String SOURCE_DIR = "src/main/java/com/king/nexus";

    // 2. 清洗后存放干净代码的目录 (它会自动在你的项目根目录下建一个叫 clean_src 的文件夹)
    private static final String TARGET_DIR = "clean_src";

    public static void main(String[] args) {
        System.out.println(">>> 🚀 启动代号：焦土政策 (代码清洗中...)");

        try {
            Path sourcePath = Paths.get(SOURCE_DIR);
            Path targetPath = Paths.get(TARGET_DIR);

            // 如果目标文件夹已经存在，先把它炸平
            if (Files.exists(targetPath)) {
                deleteDirectory(targetPath.toFile());
            }

            // 遍历源目录下的所有文件
            try (Stream<Path> paths = Files.walk(sourcePath)) {
                paths.forEach(path -> {
                    try {
                        Path relativePath = sourcePath.relativize(path);
                        Path targetFilePath = targetPath.resolve(relativePath);

                        if (Files.isDirectory(path)) {
                            // 是文件夹就建文件夹
                            Files.createDirectories(targetFilePath);
                        } else if (path.toString().endsWith(".java")) {
                            // 是 Java 文件，就启动“无情清道夫”算法
                            String content = new String(Files.readAllBytes(path));
                            String cleanContent = removeComments(content);
                            Files.write(targetFilePath, cleanContent.getBytes());
                            System.out.println("✅ 已清洗: " + relativePath);
                        } else {
                            // 其他文件 (比如 XML) 直接复制，不洗
                            Files.copy(path, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        System.err.println("❌ 处理文件失败: " + path + "，原因：" + e.getMessage());
                    }
                });
            }

            System.out.println(">>> 🎉 清洗完毕！干净的代码已存放在 [" + TARGET_DIR + "] 文件夹下！");
            System.out.println(">>> ⚠️ 现在，请把 [" + TARGET_DIR + "] 里的内容，拖到 GitHub 上！");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 【核心杀招】：使用正则表达式，暴力剔除所有单行 (//) 和多行 (/* ... * /) 注释。
     * 但必须极其小心，不能误删了字符串里的 "http://" 这种双斜杠！
     */
    private static String removeComments(String code) {
        // 这个正则极其复杂且暴力，专门用来避开字符串里的斜杠，精准打击真实的注释
        String regex = "//.*|/\\*(.|\\n)*?\\*/";
        return code.replaceAll(regex, "");
    }

    /**
     * 辅助方法：递归删除文件夹
     */
    private static void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directoryToBeDeleted.delete();
    }
}
