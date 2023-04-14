package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.SketchController;
import processing.app.debug.RunnerException;
import processing.app.helpers.PreferencesMapException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IDECompilationHook {

    @FunctionalInterface
    public interface HookExecutable {
        boolean run(Editor editor, LogProxy<EditorConsole> logProxy, Map<String, Object> context, Runnable original);
    }

    public static class SketchControllerProxy extends SketchController {

        private final Editor editor;
        private final LogProxy<EditorConsole> logProxy;
        private final HookExecutable beforeHook;
        private final HookExecutable afterHook;

        public SketchControllerProxy(Editor editor, LogProxy<EditorConsole> logProxy,
                HookExecutable beforeHook, HookExecutable afterHook) {

            super(editor, editor.getSketch());
            this.editor = editor;
            this.logProxy = logProxy;
            this.beforeHook = beforeHook;
            this.afterHook = afterHook;
        }

        @Override
        public String build(final boolean verbose, final boolean save) throws RunnerException, PreferencesMapException, IOException {
            Map<String, Object> ctx = new ConcurrentHashMap<>();
            String result = null;
            if (beforeHook == null || beforeHook.run(this.editor, this.logProxy, ctx, null)) {
                try {
                    result = super.build(verbose, save);
                } finally {
                    if (afterHook != null) {
                        afterHook.run(this.editor, this.logProxy, ctx, null);
                    }
                    ctx.clear();
                }
            }

            return result;
        }
    }

    private final LogProxy<EditorConsole> logProxy;

    private final Editor editor;
    private HookExecutable myBefore;
    private HookExecutable myAfter;

    private Runnable verboseHandler;
    private Runnable nonverboseHandler;

    public IDECompilationHook(Editor editor, LogProxy<EditorConsole> logProxy, HookExecutable before, HookExecutable after) {

        this.editor = editor;
        this.logProxy = logProxy;
        this.myBefore = before;
        this.myAfter = after;


        try {
            this.verboseHandler = this.replaceFieldValue(editor, "presentHandler", () -> {
                Map<String, Object> context = new ConcurrentHashMap<>();
                if (myBefore == null || myBefore.run(this.editor, this.logProxy, context, verboseHandler)) {
                    try {
                        verboseHandler.run();
                    } finally {
                        if (myAfter != null) {
                            myAfter.run(this.editor, this.logProxy, context, verboseHandler);
                        }
                        context.clear();
                    }
                }
            });
        } catch (Exception e) {
            logProxy.cliErrorln(e);
        }

        try {
            this.nonverboseHandler = this.replaceFieldValue(editor, "runHandler", () -> {
                Map<String, Object> context = new ConcurrentHashMap<>();
                if (myBefore == null || myBefore.run(this.editor, this.logProxy, context, verboseHandler)) {
                    try {
                        nonverboseHandler.run();
                    } finally {
                        if (myAfter != null) {
                            myAfter.run(this.editor, this.logProxy, context, verboseHandler);
                        }
                        context.clear();
                    }
                }
            });
        } catch (Exception e) {
            logProxy.cliErrorln(e);
        }
    }
    private <T> T replaceFieldValue(Object from, String fieldName, T newValue) throws NoSuchFieldException, IllegalAccessException {
        Field declaredField = from.getClass().getDeclaredField(fieldName);
        declaredField.setAccessible(true);
        T old = (T) declaredField.get(from);
        declaredField.set(from, (Object) newValue);
        return old;
    }

    public HookExecutable getBeforeHook() {
        return myBefore;
    }

    public HookExecutable getAfterHook() {
        return myAfter;
    }

    public void hookOnSketchController() {
        try {
            this.replaceFieldValue(this.editor, "sketchController", new SketchControllerProxy(
                    this.editor, this.logProxy, this.getBeforeHook(), this.getAfterHook())
            );
        } catch (Exception e) {
            logProxy.cliErrorln(e);
        }
    }
}
