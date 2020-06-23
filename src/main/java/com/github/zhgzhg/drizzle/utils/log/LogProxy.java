package com.github.zhgzhg.drizzle.utils.log;

import processing.app.EditorConsole;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

public class LogProxy {
    private static final PrintStream dummyPrintStream = new PrintStream(
            new OutputStream() { @Override public void write(final int b) throws IOException { }}) { };

    private EditorConsole editorConsole;

    public void setEditorConsole(final EditorConsole editorConsole) {
        this.editorConsole = editorConsole;
    }

    public EditorConsole getEditorConsole() {
        return editorConsole;
    }

    protected void beforeStdReturn() {
        if (this.editorConsole != null) {
            EditorConsole.setCurrentEditorConsole(this.editorConsole);
        }
    }

    public PrintStream stderr() { beforeStdReturn(); return System.err; }

    public PrintStream stdout() { beforeStdReturn(); return System.out; }

    public PrintStream stdwarn() { beforeStdReturn(); return System.out; }

    public PrintStream stdnull() { return dummyPrintStream; }


    public void cliError(String format, Object... params) { stderr().printf(format, params); }

    public void cliErrorln() { stderr().println(); }

    public void cliErrorln(String msg) { stderr().println(msg); }

    public void cliErrorln(Throwable t) { t.printStackTrace(stderr()); }

    public void cliInfo(String format, Object... params) { stdout().printf(format, params); }

    public void cliInfoln() { stdout().println(); }

    public void cliInfoln(String msg) { stdout().println(msg); }

    public void cliInfoln(Throwable t) { t.printStackTrace(stdout()); }

    public void cliWarn(String format, Object... params) { stdwarn().printf(format, params); }

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
