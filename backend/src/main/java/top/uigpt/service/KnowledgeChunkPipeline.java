package top.uigpt.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 知识库导入：段落 → 句号切分 → 关键词/语义一致性合并为 chunk。
 */
public final class KnowledgeChunkPipeline {

    /** 单条向量文本建议上限 */
    private static final int MAX_CHARS_PER_CHUNK = 1200;
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
            out.addAll(mergeByKeywordAndSemantic(expanded));
        }
        return out;
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

    static List<String> mergeByKeywordAndSemantic(List<String> sentences) {
        if (sentences.isEmpty()) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String sent : sentences) {
            if (sent == null || sent.isBlank()) {
                continue;
            }
            if (buf.isEmpty()) {
                buf.append(sent);
                continue;
            }
            String prev = buf.toString();
            String cand = prev + sent;
            if (cand.length() > MAX_CHARS_PER_CHUNK) {
                merged.add(prev);
                buf.setLength(0);
                buf.append(sent);
                continue;
            }
            double jac = bigramJaccard(prev, sent);
            double dice = trigramDice(prev, sent);
            boolean shortBridge =
                    sent.length() <= SHORT_SENTENCE_CHARS || prev.length() <= SHORT_SENTENCE_CHARS;
            if ((jac >= MIN_KEYWORD_JACCARD && dice >= MIN_SEMANTIC_DICE) || (shortBridge && dice >= 0.12)) {
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
