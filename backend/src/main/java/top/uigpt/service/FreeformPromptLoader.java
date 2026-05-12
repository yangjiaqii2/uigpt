package top.uigpt.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 「自由对话」技能系统提示（classpath UTF-8）。 */
final class FreeformPromptLoader {

    private FreeformPromptLoader() {}

    static String loadText() {
        try {
            return readUtf8("prompts/freeform-skill-system.txt");
        } catch (IOException e) {
            return "你是全能 AI 助手：友好、简洁、专业；可使用 Markdown 作答。";
        }
    }

    private static String readUtf8(String classpathLocation) throws IOException {
        ClassLoader cl = FreeformPromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Missing resource: " + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
