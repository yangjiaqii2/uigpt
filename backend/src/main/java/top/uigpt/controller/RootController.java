package top.uigpt.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 避免直接访问后端根路径时出现英文 Whitelabel 404。 */
@RestController
public class RootController {

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> root() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("message", "此为后端 API 服务，不提供网页界面。");
        m.put("tip", "请在浏览器访问前端站点（一般为 80 端口）；REST 接口前缀为 /api。");
        return m;
    }
}
