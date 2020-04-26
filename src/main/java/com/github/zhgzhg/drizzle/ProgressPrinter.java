package com.github.zhgzhg.drizzle;

class ProgressPrinter {
    private int index;
    private int step;
    private String msg;
    private int lineBreakIndex;
    private int printIndex;

    public void begin(int step, int printIndex, int lineBreakIndex, String msg) {
        this.index = 0;
        this.step = step;
        this.printIndex = printIndex;
        this.lineBreakIndex = lineBreakIndex;
        this.msg = msg;
    }

    public void progress() {
        if (index == lineBreakIndex) {
            System.out.println();
            index = 0;
        }
        if (printIndex < 0 || (printIndex > -1 && (index % printIndex == 0))) {
            System.out.print(msg);
        }
        index += step;
    }
}
