package com.github.zhgzhg.drizzle.utils.log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Supplier;

public class LogProxy<T> {

    // Mitigate log4j2 vulnerability (CVE-2021-44228), in case log4j is added to the classpath as the logger
    // https://blog.cloudflare.com/inside-the-log4j2-vulnerability-cve-2021-44228/
    static {
        System.getProperties().setProperty("log4j2.formatMsgNoLookups", "true");
    }

    private static final PrintStream dummyPrintStream = new PrintStream(
            new OutputStream() { @Override public void write(final int b) throws IOException { }}) { };

    public interface EditorConsoleSupplierAndSetter<T> extends Supplier<T>, Runnable {

    }

    private EditorConsoleSupplierAndSetter<T> editorConsoleSupplierAndSetter;

    public void setEditorConsole(final EditorConsoleSupplierAndSetter<T> editorConsoleSupplierAndSetter) {
        this.editorConsoleSupplierAndSetter = editorConsoleSupplierAndSetter;
    }

    public T getEditorConsole() {
        return editorConsoleSupplierAndSetter.get();
    }

    protected void beforeStdReturn() {
        if (this.editorConsoleSupplierAndSetter != null) {
            editorConsoleSupplierAndSetter.run();
        }
    }

    public PrintStream stderr() { beforeStdReturn(); return System.err; }

    public PrintStream stdout() { beforeStdReturn(); return System.out; }

    public PrintStream stdwarn() { beforeStdReturn(); return System.out; }

    public PrintStream stdnull() { return dummyPrintStream; }


    public void cliError(String format, Object... params) { stderr().printf(format != null ? format : "null", params); }

    public void cliErrorln() { stderr().println(); }

    public void cliErrorln(String msg) { stderr().println(msg); }

    public void cliErrorln(Throwable t) { t.printStackTrace(stderr()); }

    public void cliInfo(String format, Object... params) { stdout().printf(format != null ? format : "null", params); }

    public void cliInfoln() { stdout().println(); }

    public void cliInfoln(String msg) { stdout().println(msg); }

    public void cliInfoln(Throwable t) { t.printStackTrace(stdout()); }

    public void cliWarn(String format, Object... params) { stdwarn().printf(format != null ? format : "null", params); }

    public void cliWarnln() { stdwarn().println(); }

    public void cliWarnln(String msg) { stdwarn().println(msg); }

    public void cliWarnln(Throwable t) { t.printStackTrace(stdwarn()); }

    public void uiError(String format, Object... params) {
        // to be defined by the UI component if needed
    }

    public void uiError(Throwable t) {
        // to be defined by the UI component if needed
    }

    public void uiInfo(String format, Object... params) {
        // to be defined by the UI component if needed
    }

    public void uiWarn(String format, Object... params) {
        // to be defined by the UI component if needed
    }
}
