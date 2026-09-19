package com.raditha.dedup.cli;

import java.io.PrintStream;

/**
 * Writes command-line output to a configurable stream.
 * This is the single sanctioned stdout/stderr sink for command-line output.
 */
@SuppressWarnings("java:S106")
public final class ConsoleWriter {

    private final PrintStream out;
    private final PrintStream err;

    public ConsoleWriter() {
        this(System.out, System.err);
    }

    public ConsoleWriter(PrintStream out) {
        this(out, System.err);
    }

    public ConsoleWriter(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
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

    public void errln(String value) {
        err.println(value);
    }
}
