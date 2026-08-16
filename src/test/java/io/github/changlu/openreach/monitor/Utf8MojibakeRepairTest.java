package io.github.changlu.openreach.monitor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Utf8MojibakeRepairTest {
    @Test
    void repairsUtf8BytesPreviouslyDecodedAsLatin1() {
        String expected = "{\"query\":\"大模型 AI Agent 最新发布 2026年8月\",\"title\":\"AI应用周度观察（智能体）\"}";
        String mojibake = new String(expected.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertNotEquals(expected, mojibake);
        assertEquals(expected, Utf8MojibakeRepair.repairIfNeeded(mojibake));
    }

    @Test
    void leavesCorrectUnicodeAndOrdinaryLatinTextUntouched() {
        assertEquals("中文正常内容", Utf8MojibakeRepair.repairIfNeeded("中文正常内容"));
        assertEquals("café naïve", Utf8MojibakeRepair.repairIfNeeded("café naïve"));
        assertEquals("plain ascii", Utf8MojibakeRepair.repairIfNeeded("plain ascii"));
    }
}
