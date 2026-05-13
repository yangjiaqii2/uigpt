package top.uigpt.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 知识库导入：段落 → 句号切分 → 关键词/语义一致性合并为 chunk。
 *
 * <p>合并判定用「左侧文本尾部」与「右侧下一句」算 n-gram 重合，避免缓冲区已很长时整段与短句比较导致相似度被稀释（同主题句合并不了）。
 *
 * <p>单 chunk 目标约 200～300 字（{@link String#length()}）；过短会并入下一块（不超过上限），过长先按弱标点或硬切分。
 */
public final class KnowledgeChunkPipeline {

    /** 单 chunk 目标字数下限 */
    private static final int CHUNK_TARGET_MIN_CHARS = 200;
    /** 单 chunk 目标字数上限 */
    private static final int CHUNK_TARGET_MAX_CHARS = 300;
    /** 与下一句/下一块比较相似度时，左侧只取末尾若干字参与 n-gram */
    private static final int MERGE_COMPARE_TAIL_CHARS = 320;
    /** 合并：字符二元组 Jaccard 下限 */
    private static final double MIN_KEYWORD_JACCARD = 0.14;
    /** 合并：字符三元组 Dice 下限（语意稳定性） */
    private static final double MIN_SEMANTIC_DICE = 0.22;
    /** 过短句可与相邻句合并时的长度阈值 */
    private static final int SHORT_SENTENCE_CHARS = 28;

    private KnowledgeChunkPipeline() {}

    public static List<String> buildChunks(String rawText, UnaryOperator<String> complexSentencePolisher) {
        String normalized = normalizeUnifiedText(rawText);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> paragraphs = splitParagraphs(normalized);
        List<String> out = new ArrayList<>();
        UnaryOperator<String> polish = complexSentencePolisher == null ? UnaryOperator.identity() : complexSentencePolisher;
        for (String para : paragraphs) {
            List<String> sentences = splitSentences(para);
            List<String> expanded = new ArrayList<>();
            for (String s : sentences) {
                String p = polish.apply(s);
                String flat = p.replace('\n', ' ').strip();
                List<String> subs = splitSentences(flat);
                if (subs.isEmpty()) {
                    if (!flat.isEmpty()) {
                        expanded.add(flat);
                    }
                } else {
                    expanded.addAll(subs);
                }
            }
            out.addAll(mergeAdjacentTextUnits(expanded));
        }
        // 段与段之间未参与上文合并；对已形成 chunk 再扫一遍相邻块
        List<String> twice = mergeAdjacentTextUnits(out);
        return packUndersizedChunks(twice);
    }

    static String normalizeUnifiedText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        return s.strip();
    }

    static List<String> splitParagraphs(String text) {
        String[] parts = text.split("\n\\s*\n+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String t = p == null ? "" : p.strip();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        if (list.isEmpty() && !text.isBlank()) {
            list.add(text.strip());
        }
        return list;
    }

    static List<String> splitSentences(String paragraph) {
        List<String> out = new ArrayList<>();
        if (paragraph == null || paragraph.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char c = paragraph.charAt(i);
            cur.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                String s = cur.toString().strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().strip();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /**
     * 将相邻「文本单元」（句号切分后的句，或已是多句合并的 chunk）按 n-gram 相似度合并。
     */
    static List<String> mergeAdjacentTextUnits(List<String> parts) {
        if (parts.isEmpty()) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            units.addAll(splitLongUnitsForMaxChunk(p.strip(), CHUNK_TARGET_MAX_CHARS));
        }
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String sent : units) {
            if (sent.isBlank()) {
                continue;
            }
            if (buf.isEmpty()) {
                buf.append(sent);
                continue;
            }
            String prev = buf.toString();
            String cand = prev + sent;
            if (cand.length() > CHUNK_TARGET_MAX_CHARS) {
                merged.add(prev);
                buf.setLength(0);
                buf.append(sent);
                continue;
            }
            boolean similar = shouldMergeAdjacentRuns(prev, sent);
            boolean needFill = prev.length() < CHUNK_TARGET_MIN_CHARS;
            if (similar || needFill) {
                buf.append(sent);
            } else {
                merged.add(prev);
                buf.setLength(0);
                buf.append(sent);
            }
        }
        if (!buf.isEmpty()) {
            merged.add(buf.toString());
        }
        return merged;
    }

    /**
     * 将超长单元切成不超过 {@code maxChars} 的多段（优先在逗号、分号等弱标点处断开）。
     */
    static List<String> splitLongUnitsForMaxChunk(String s, int maxChars) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        if (s.length() <= maxChars) {
            return List.of(s);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < s.length()) {
            int remaining = s.length() - start;
            if (remaining <= maxChars) {
                String tail = s.substring(start).strip();
                if (!tail.isEmpty()) {
                    out.add(tail);
                }
                break;
            }
            int proposedEnd = start + maxChars;
            int cut = proposedEnd;
            int searchFrom = Math.max(start + 1, proposedEnd - maxChars / 2);
            for (int j = proposedEnd - 1; j >= searchFrom; j--) {
                if (isWeakBreakChar(s.charAt(j))) {
                    cut = j + 1;
                    break;
                }
            }
            String piece = s.substring(start, cut).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = cut;
            while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
                start++;
            }
        }
        return out;
    }

    private static boolean isWeakBreakChar(char c) {
        return c == '，'
                || c == '、'
                || c == '；'
                || c == ';'
                || c == '：'
                || c == ':'
                || c == ','
                || c == '\n';
    }

    /**
     * 合并后仍有过短块时，在不超过字数上限前提下与相邻块拼接（避免大量几十字的碎片）。
     */
    static List<String> packUndersizedChunks(List<String> chunks) {
        List<String> list = new ArrayList<>();
        for (String c : chunks) {
            if (c != null && !c.isBlank()) {
                list.add(c.strip());
            }
        }
        if (list.size() <= 1) {
            return list;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < list.size() - 1; i++) {
                String a = list.get(i);
                String b = list.get(i + 1);
                int sum = a.length() + b.length();
                if (sum <= CHUNK_TARGET_MAX_CHARS
                        && (a.length() < CHUNK_TARGET_MIN_CHARS || b.length() < CHUNK_TARGET_MIN_CHARS)) {
                    list.set(i, a + b);
                    list.remove(i + 1);
                    changed = true;
                    break;
                }
            }
        }
        return list;
    }

    /** 供测试与文档：旧名即 {@link #mergeAdjacentTextUnits} */
    static List<String> mergeByKeywordAndSemantic(List<String> sentences) {
        return mergeAdjacentTextUnits(sentences);
    }

    /**
     * 是否将右侧并入左侧缓冲区：相似度在「左侧尾部」与右侧之间计算，避免长缓冲稀释重合度。
     */
    static boolean shouldMergeAdjacentRuns(String prevFull, String nextPart) {
        String left = leftSideForSimilarity(prevFull, MERGE_COMPARE_TAIL_CHARS);
        double jac = bigramJaccard(left, nextPart);
        double dice = trigramDice(left, nextPart);
        boolean shortBridge =
                nextPart.length() <= SHORT_SENTENCE_CHARS || prevFull.length() <= SHORT_SENTENCE_CHARS;
        return (jac >= MIN_KEYWORD_JACCARD && dice >= MIN_SEMANTIC_DICE) || (shortBridge && dice >= 0.12);
    }

    static String leftSideForSimilarity(String prevFull, int maxTailChars) {
        if (prevFull == null || prevFull.isEmpty()) {
            return "";
        }
        if (prevFull.length() <= maxTailChars) {
            return prevFull;
        }
        return prevFull.substring(prevFull.length() - maxTailChars);
    }

    static double bigramJaccard(String a, String b) {
        Set<String> sa = charBigrams(normForNgram(a));
        Set<String> sb = charBigrams(normForNgram(b));
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String x : sa) {
            if (sb.contains(x)) {
                inter++;
            }
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    static double trigramDice(String a, String b) {
        Set<String> sa = charTrigrams(normForNgram(a));
        Set<String> sb = charTrigrams(normForNgram(b));
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String x : sa) {
            if (sb.contains(x)) {
                inter++;
            }
        }
        return (double) (2 * inter) / (sa.size() + sb.size());
    }

    private static String normForNgram(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> charBigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() < 2) {
            return set;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    private static Set<String> charTrigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() < 3) {
            return set;
        }
        for (int i = 0; i + 2 < s.length(); i++) {
            set.add(s.substring(i, i + 3));
        }
        return set;
    }
}
