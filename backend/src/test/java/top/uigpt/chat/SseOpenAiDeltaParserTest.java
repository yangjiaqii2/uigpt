package top.uigpt.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseOpenAiDeltaParserTest {

    @Test
    void extractsStringContentFromDelta() {
        String data =
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}";
        assertEquals("你好", SseOpenAiDeltaParser.extractStreamText(data));
    }

    @Test
    void extractsFromMessageWhenDeltaEmpty() {
        String data =
                "{\"choices\":[{\"index\":0,\"delta\":{},\"message\":{\"role\":\"assistant\",\"content\":\"x\"}}]}";
        assertEquals("x", SseOpenAiDeltaParser.extractStreamText(data));
    }

    @Test
    void extractsArrayTextParts() {
        String data =
                "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}]}";
        assertEquals("ab", SseOpenAiDeltaParser.extractStreamText(data));
    }
}
