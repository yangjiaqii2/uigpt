package top.uigpt.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 「AI 修图」技能系统提示（classpath UTF-8）。 */
final class RetouchPromptLoader {

    private RetouchPromptLoader() {}

    static String loadText() {
        try {
            return readUtf8("prompts/ai-retouch-skill-system.txt");
        } catch (IOException e) {
            return "你是专业 AI 图像编辑师：分析参考图与用户诉求，给出可执行的修图方案与生图提示词。";
        }
    }

    private static String readUtf8(String classpathLocation) throws IOException {
        ClassLoader cl = RetouchPromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Missing resource: " + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
