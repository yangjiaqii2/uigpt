package top.uigpt.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 装修「效果图设计」技能的完整系统提示（classpath UTF-8 文本拼接）。 */
final class InteriorMockupPromptLoader {

    private InteriorMockupPromptLoader() {}

    static String loadCombinedText() {
        try {
            return readUtf8("prompts/interior-mockup-skill-system-1.txt")
                    + readUtf8("prompts/interior-mockup-skill-system-2.txt");
        } catch (IOException e) {
            return "你是资深室内设计师与效果图渲染专家，请结合用户描述与面板参数给出装修方案与可执行的渲染提示词。";
        }
    }

    private static String readUtf8(String classpathLocation) throws IOException {
        ClassLoader cl = InteriorMockupPromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Missing resource: " + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
