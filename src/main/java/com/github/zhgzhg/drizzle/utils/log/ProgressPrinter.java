package com.github.zhgzhg.drizzle.utils.log;

public class ProgressPrinter {
    private int index;
    private int step;
    private String msg;
    private int lineBreakIndex;
    private int printIndex;
    private LogProxy logProxy;

    public ProgressPrinter(LogProxy logProxy) {
        this.logProxy = logProxy;
    }

    public void begin(int step, int printIndex, int lineBreakIndex, String msg) {
        this.index = 0;
        this.step = step;
        this.printIndex = printIndex;
        this.lineBreakIndex = lineBreakIndex;
        this.msg = msg;
    }

    public void progress() {
        if (index == lineBreakIndex) {
            this.logProxy.cliInfoln();
            index = 0;
        }
        if (printIndex < 0 || (index % printIndex == 0)) {
            this.logProxy.cliInfo(msg);
        }
        index += step;
    }
}
