package top.uigpt.imagestudio.orchestration;

import org.springframework.stereotype.Service;

@Service
public class DefaultImageMemoryService implements ImageMemoryService {

    private static final int SESSION_CONTEXT_CAP = 8000;

    @Override
    public String mergeForApi(String userPrompt, String sessionContext) {
        String p = userPrompt == null ? "" : userPrompt.strip();
        String c = sessionContext == null ? "" : sessionContext.strip();
        if (c.isEmpty()) {
            return p;
        }
        if (c.length() > SESSION_CONTEXT_CAP) {
            c = c.substring(0, SESSION_CONTEXT_CAP) + "…";
        }
        if (p.isEmpty()) {
            return "【当前图片会话上下文】\n" + c;
        }
        return "【当前图片会话上下文】\n" + c + "\n\n——\n【本次指令】\n" + p;
    }
}
