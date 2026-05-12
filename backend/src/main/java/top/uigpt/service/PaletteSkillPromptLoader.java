package top.uigpt.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从 classpath 加载「配色方案」技能的完整系统提示（ UTF-8 文本）。 */
final class PaletteSkillPromptLoader {

    private PaletteSkillPromptLoader() {}

    static String loadCombinedText() {
        try {
            return readUtf8("prompts/palette-skill-system-1.txt")
                    + readUtf8("prompts/palette-skill-system-2.txt");
        } catch (IOException e) {
            return "你是一位资深色彩设计师，请根据用户需求与面板参数生成专业、可落地的配色方案（含 HEX/RGB、角色分工与应用建议）。";
        }
    }

    private static String readUtf8(String classpathLocation) throws IOException {
        ClassLoader cl = PaletteSkillPromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Missing resource: " + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
