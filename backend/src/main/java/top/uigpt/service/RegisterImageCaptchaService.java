package top.uigpt.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RegisterCaptchaResponse;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册用图形验证码：图片上为「两整数加法」算式，校验用户填写的**数字结果**（进程内存储，一次性校验）。
 */
@Service
public class RegisterImageCaptchaService {

    private static final long TTL_MS = 300_000;
    private static final int IMG_W = 160;
    private static final int IMG_H = 48;

    private record Entry(String answer, long expireAtMillis) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 生成新验证码图片（PNG Base64） */
    public RegisterCaptchaResponse create() {
        prune();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int a = r.nextInt(1, 20);
        int b = r.nextInt(1, 20);
        int sum = a + b;
        String answer = Integer.toString(sum);
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(answer, System.currentTimeMillis() + TTL_MS));
        String b64 = renderPngBase64(a, b);
        return new RegisterCaptchaResponse(id, b64);
    }

    /**
     * 校验用户输入并作废该 captchaId（无论对错，校验后即删除，需重新获取图片）。
     */
    public void validateAndConsume(String captchaId, String userInput) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先获取图形验证码");
        }
        if (userInput == null || userInput.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入验证码结果");
        }
        Entry entry = store.remove(captchaId.trim());
        if (entry == null || System.currentTimeMillis() > entry.expireAtMillis) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码已失效，请点击图片刷新");
        }
        String normalized = userInput.strip().replaceAll("\\s+", "");
        if (!normalized.chars().allMatch(Character::isDigit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码须为数字");
        }
        if (!entry.answer.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
    }

    private static String renderPngBase64(int a, int b) {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, IMG_W, IMG_H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            g.setColor(new Color(230, 235, 245));
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < 6; i++) {
                g.drawLine(rnd.nextInt(IMG_W), rnd.nextInt(IMG_H), rnd.nextInt(IMG_W), rnd.nextInt(IMG_H));
            }
            g.setColor(new Color(35, 45, 70));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            String text = a + " + " + b + " = ?";
            int sw = g.getFontMetrics().stringWidth(text);
            g.drawString(text, Math.max(8, (IMG_W - sw) / 2), 34);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("生成验证码图片失败", e);
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expireAtMillis < now);
    }
}
