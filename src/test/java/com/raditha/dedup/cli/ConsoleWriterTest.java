package com.raditha.dedup.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleWriterTest {

    @Test
    void writesAllSupportedFormatsToConfiguredStream() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleWriter writer = new ConsoleWriter(new PrintStream(output, true, StandardCharsets.UTF_8));

        writer.println("line");
        writer.println();
        writer.print("value");
        writer.printf(" %d%n", 42);

        assertEquals("line" + System.lineSeparator() + System.lineSeparator() + "value 42"
                + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void defaultWriterUsesSystemOut() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            new ConsoleWriter().println("default");
            assertEquals("default" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }
}
