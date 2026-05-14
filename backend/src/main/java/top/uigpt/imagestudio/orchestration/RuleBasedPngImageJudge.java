package top.uigpt.imagestudio.orchestration;

import org.springframework.stereotype.Component;

/**
 * 无额外模型费用的启发式：校验 PNG 魔数并略奖励有效载荷长度（越大略加分，有上限）。
 */
@Component
public class RuleBasedPngImageJudge implements ImageCandidateJudge {

    private static final long PNG_HEADER = 0x89504E470D0A1A0AL;

    @Override
    @SuppressWarnings("unused")
    public double score(byte[] pngBytes, String userGoalText) {
        if (pngBytes == null || pngBytes.length < 24) {
            return Double.NEGATIVE_INFINITY;
        }
        if (!looksLikePng(pngBytes)) {
            return Double.NEGATIVE_INFINITY;
        }
        double sizeBonus = Math.min(pngBytes.length / 5000.0, 200.0);
        return 1000.0 + sizeBonus;
    }

    private static boolean looksLikePng(byte[] b) {
        if (b.length < 8) {
            return false;
        }
        long head = 0;
        for (int i = 0; i < 8; i++) {
            head = (head << 8) | (b[i] & 0xffL);
        }
        return head == PNG_HEADER;
    }
}
