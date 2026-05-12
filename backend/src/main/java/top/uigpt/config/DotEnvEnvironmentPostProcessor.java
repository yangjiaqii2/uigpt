package top.uigpt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从 <code>.env</code> 注入属性（不提交仓库），解决 IDE 直接运行未 <code>source .env</code> 时读不到
 * <code>APIYI_*</code> 等问题。
 *
 * <p>若设置环境变量 {@code UIGPT_DOTENV_FILE} 且指向可读文件，则<strong>仅加载该路径</strong>（Docker / 任意工作目录推荐）。
 *
 * <p>否则自 {@code user.dir} 起向上最多 8 层目录查找：每层先试 {@code .env}，再试 {@code backend/.env}；仍无时尝试
 * {@code java -jar app.jar} 场景下 <strong>jar 同级目录</strong>的 {@code .env}。
 *
 * <p>格式：<code>KEY=value</code>，<code>#</code> 行与空行忽略。须在解析 <code>application.yml</code> 占位符<strong>之前</strong>执行（见 {@link #getOrder()}），否则
 * <code>${APIYI_IMAGE_ENABLED:false}</code> 会先落成 false。
 *
 * <p>插入在 {@link StandardEnvironment#SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME JVM systemProperties} <strong>之前</strong>，以便盖住空的 {@code -DAPIYI_API_KEY=} 与 shell 里空的 {@code export APIYI_API_KEY=}；
 * 若 {@link System#getenv(String)} 或 {@link System#getProperty(String)} 对某键已有<strong>非空</strong>值，则不写入该键、仍以显式外部配置为准。
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DotEnvEnvironmentPostProcessor.class);

    private static final String SOURCE_NAME = "dotEnvFile";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).normalize();
        Path envFile = resolveDotEnvFile(cwd);
        if (envFile == null) {
            return;
        }
        Map<String, Object> raw = parseDotEnv(envFile);
        if (raw.isEmpty()) {
            return;
        }
        Map<String, Object> toAdd = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if (externalNonBlank(key)) {
                continue;
            }
            toAdd.put(key, e.getValue());
        }
        if (!toAdd.isEmpty()) {
            MapPropertySource ps = new MapPropertySource(SOURCE_NAME, toAdd);
            MutablePropertySources sources = environment.getPropertySources();
            String sysProps = StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME;
            String sysEnv = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
            if (sources.contains(sysProps)) {
                sources.addBefore(sysProps, ps);
            } else if (sources.contains(sysEnv)) {
                sources.addBefore(sysEnv, ps);
            } else {
                sources.addFirst(ps);
            }
            log.info(
                    "已从 {} 注入 {} 条变量（插在 systemProperties 之前；非空 OS/-D 仍优先）",
                    envFile.toAbsolutePath(),
                    toAdd.size());
        }
    }

    /** OS 或 {@code -D} 已给出非空值时不再用 .env 覆盖（部署环境显式注入优先）。 */
    static boolean externalNonBlank(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return true;
        }
        String prop = System.getProperty(key);
        return prop != null && !prop.isBlank();
    }

    /** 优先 {@code UIGPT_DOTENV_FILE}，否则自 cwd 起向上查找 {@code .env} 或 {@code backend/.env}。 */
    static Path resolveDotEnvFile(Path cwd) {
        String explicit = System.getenv("UIGPT_DOTENV_FILE");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit.strip()).normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
            log.warn("UIGPT_DOTENV_FILE 指向的文件不存在或不可读: {}", p.toAbsolutePath());
        }
        Path cur = cwd.normalize();
        for (int depth = 0; depth < 8; depth++) {
            Path direct = cur.resolve(".env");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path nested = cur.resolve("backend").resolve(".env");
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            Path parent = cur.getParent();
            if (parent == null || parent.equals(cur)) {
                break;
            }
            cur = parent;
        }
        Path jarSibling = jarSiblingDotEnv();
        if (jarSibling != null) {
            return jarSibling;
        }
        return null;
    }

    /** {@code java -jar xxx.jar} 时 classpath 首段常为 jar 路径，取其同级 {@code .env}。 */
    static Path jarSiblingDotEnv() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isBlank()) {
            return null;
        }
        String[] parts = cp.split(Pattern.quote(File.pathSeparator));
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        Path entry = Paths.get(parts[0].strip()).normalize();
        if (!Files.isRegularFile(entry)) {
            return null;
        }
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar")) {
            return null;
        }
        Path parent = entry.getParent();
        if (parent == null) {
            return null;
        }
        Path env = parent.resolve(".env");
        return Files.isRegularFile(env) ? env : null;
    }

    static Map<String, Object> parseDotEnv(Path envFile) {
        Map<String, Object> map = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = t.substring(0, eq).strip();
                if (!key.isEmpty() && key.charAt(0) == '\uFEFF') {
                    key = key.substring(1).strip();
                }
                String val = t.substring(eq + 1).strip();
                if ((val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2)
                        || (val.startsWith("'") && val.endsWith("'") && val.length() >= 2)) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!key.isEmpty()) {
                    map.put(key, val);
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return map;
    }
}
