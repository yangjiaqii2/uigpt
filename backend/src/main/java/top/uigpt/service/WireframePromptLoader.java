package top.uigpt.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 「原型图设计」技能系统提示（classpath UTF-8）。 */
final class WireframePromptLoader {

    private WireframePromptLoader() {}

    static String loadText() {
        try {
            return readUtf8("prompts/wireframe-prototype-skill-system.txt");
        } catch (IOException e) {
            return "你是专业 UX/UI 原型设计师：输出信息架构、线框描述与交互逻辑；线框风黑白灰为主，关键交互可用蓝色标注。";
        }
    }

    private static String readUtf8(String classpathLocation) throws IOException {
        ClassLoader cl = WireframePromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Missing resource: " + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
