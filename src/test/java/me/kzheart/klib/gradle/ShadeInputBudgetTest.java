package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShadeInputBudgetTest {
    @Test
    void boundedReaderRejectsExpandedContentPastTheRemainingBudget() {
        assertThrows(IOException.class, () -> KlibShadeJarTask.readBounded(
                new ByteArrayInputStream(new byte[9]), 8L, "bomb.bin"));
    }

    @Test
    void boundedReaderPreservesLegitimateContent() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        assertArrayEquals(content, KlibShadeJarTask.readBounded(
                new ByteArrayInputStream(content), 8L, "safe.bin"));
    }
}
