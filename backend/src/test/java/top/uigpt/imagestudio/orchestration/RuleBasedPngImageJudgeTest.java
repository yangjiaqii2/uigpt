package top.uigpt.imagestudio.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedPngImageJudgeTest {

    private static byte[] pngLikeBytes(int length) {
        byte[] b = new byte[length];
        Arrays.fill(b, (byte) 0x2A);
        b[0] = (byte) 0x89;
        b[1] = 0x50;
        b[2] = 0x4E;
        b[3] = 0x47;
        b[4] = 0x0D;
        b[5] = 0x0A;
        b[6] = 0x1A;
        b[7] = 0x0A;
        return b;
    }

    @Test
    void prefersLargerValidPngLikePayload() {
        RuleBasedPngImageJudge judge = new RuleBasedPngImageJudge();
        double s1 = judge.score(pngLikeBytes(2000), "goal");
        double s2 = judge.score(pngLikeBytes(20_000), "goal");
        assertTrue(s2 > s1);
    }

    @Test
    void rejectsNonPng() {
        RuleBasedPngImageJudge judge = new RuleBasedPngImageJudge();
        assertEquals(Double.NEGATIVE_INFINITY, judge.score(new byte[] {1, 2, 3}, "x"));
    }
}
