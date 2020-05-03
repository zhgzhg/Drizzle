package com.github.zhgzhg.drizzle.utils.log;

import java.io.PrintStream;

public class LogProxy {
    public PrintStream stderr() { return System.err; }

    public PrintStream stdout() { return System.out; }

    public void cliError(String format, Object... params) {
        System.err.printf(format, params);
    }

    public void cliErrorln() {
        System.err.println();
    }

    public void cliErrorln(String msg) {
        System.err.println(msg);
    }

    public void cliErrorln(Throwable t) {
        t.printStackTrace(System.err);
    }

    public void cliInfo(String format, Object... params) {
        System.out.printf(format, params);
    }

    public void cliInfoln() {
        System.out.println();
    }

    public void cliInfoln(String msg) {
        System.out.println(msg);
    }

    public void cliInfoln(Throwable t) {
        t.printStackTrace(System.out);
    }

    public void uiError(String format, Object... params) {
        // to be defined by the UI component if needed
    }

    public void uiError(Throwable t) {
        // to be defined by the UI component if needed
    }

    public void uiInfo(String format, Object... params) {
        // to be defined by the UI component if needed
    }
}
