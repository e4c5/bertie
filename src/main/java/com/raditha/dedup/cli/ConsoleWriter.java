package com.raditha.dedup.cli;

import java.io.PrintStream;

/**
 * Writes command-line output to a configurable stream.
 */
public final class ConsoleWriter {

    private final PrintStream out;

    public ConsoleWriter() {
        this(System.out);
    }

    public ConsoleWriter(PrintStream out) {
        this.out = out;
    }

    public void println(String value) {
        out.println(value);
    }

    public void println() {
        out.println();
    }

    public void print(String value) {
        out.print(value);
    }

    public void printf(String format, Object... args) {
        out.printf(format, args);
    }
}
