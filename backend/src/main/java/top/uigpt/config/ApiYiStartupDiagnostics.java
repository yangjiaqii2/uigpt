package top.uigpt.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时提示 APIYi 密钥是否生效，便于排查「高速线路未配置」类问题（不打密钥明文）。
 */
@Slf4j
@Component
@Order(500)
@RequiredArgsConstructor
public class ApiYiStartupDiagnostics implements ApplicationRunner {

    private final AppProperties appProperties;

    @Override
    public void run(ApplicationArguments args) {
        String key = appProperties.getApiYiImage().getApiKey();
        if (key != null && !key.isBlank()) {
            log.info("APIYi：api-key 已配置，高速对话/出图线路可用");
            return;
        }
        String dotFile = System.getenv("UIGPT_DOTENV_FILE");
        log.info(
                "APIYi：未检测到 api-key（支持 APIYI_API_KEY、UIGPT_APIYI_API_KEY、或 .env 中同名项）。"
                        + " 高速功能将不可用。可选：export UIGPT_DOTENV_FILE=/绝对路径/backend/.env；"
                        + "或将 .env 放在进程工作目录、仓库根/backend/.env、或 fat jar 同级目录。"
                        + "{}",
                dotFile != null && !dotFile.isBlank()
                        ? " 当前 UIGPT_DOTENV_FILE=" + dotFile.strip()
                        : "");
    }
}
