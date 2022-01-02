package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.Compiler;
import cc.arduino.MessageConsumerOutputStream;
import cc.arduino.i18n.ExternalProcessOutputParser;
import cc.arduino.i18n.I18NAwareMessageConsumer;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.debug.RunnerException;
import processing.app.debug.TargetBoard;
import processing.app.debug.TargetPackage;
import processing.app.debug.TargetPlatform;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class CompilationInvoker {
    private final Editor editor;
    private final LogProxy<EditorConsole> logProxy;
    private final Consumer<String> compilerMessagesConsumer;
    private final Consumer<Integer> compilationProgressConsumer;

    public CompilationInvoker(Editor editor, LogProxy<EditorConsole> logProxy, Consumer<String> compilerMessagesConsumer,
            Consumer<Integer> compilationProgressConsumer) {
        this.editor = editor;
        this.logProxy = logProxy;
        this.compilerMessagesConsumer = compilerMessagesConsumer;
        this.compilationProgressConsumer = compilationProgressConsumer;
    }

    private void initCompilerVariables(Compiler compiler) throws RunnerException {
        try {
            Class<?> clazz = compiler.getClass();
            Field buildPath = clazz.getDeclaredField("buildPath");
            buildPath.setAccessible(true);
            buildPath.set(compiler, this.editor.getSketch().getBuildPath().getAbsolutePath());

            Field buildCache = clazz.getDeclaredField("buildCache");
            buildCache.setAccessible(true);
            buildCache.set(compiler, BaseNoGui.getCachePath());
        } catch (Exception e) {
            throw new RunnerException(e);
        }
    }

    public void compile() throws RunnerException {
        TargetBoard targetBoard = BaseNoGui.getTargetBoard();
        if (targetBoard == null) {
            throw new RunnerException("Board is not selected");
        }

        Compiler compiler = new Compiler(this.editor.getSketch());
        initCompilerVariables(compiler);

        Class<?> builderAction = Stream.of(compiler.getClass().getDeclaredClasses())
                .filter(clazz -> "BuilderAction".equals(clazz.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new RunnerException("Cannot find enum " + compiler.getClass().getCanonicalName() + ".BuilderAction"));

        Method callArduinoBuilder;
        try {
            callArduinoBuilder = compiler.getClass().getDeclaredMethod("callArduinoBuilder", TargetBoard.class,
                    TargetPlatform.class, TargetPackage.class, String.class, builderAction, OutputStream.class, OutputStream.class);

            callArduinoBuilder.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RunnerException("Cannot find method " + compiler.getClass().getCanonicalName() + ".callArduinoBuilder(...) : "
                    + e.getMessage());
        }

        Object compileAction = Stream.of(builderAction.getEnumConstants())
                .filter(constant -> "COMPILE".equals(constant.toString()))
                .findFirst()
                .orElseThrow(() -> new RunnerException("Cannot find enum constant " + compiler.getClass().getCanonicalName()
                        + ".BuilderAction.COMPILE"));

        ExternalProcessOutputParser externalProcessOutputParser = new ExternalProcessOutputParser();
        I18NAwareMessageConsumer i18NAwareMessageConsumer = new I18NAwareMessageConsumer(System.out, System.err);
        MessageConsumerOutputStream out = new MessageConsumerOutputStream(s -> {
            if (!s.startsWith("===info ||| Progress") && !s.startsWith("===Progress")) {
                compilerMessagesConsumer.accept(s);
                i18NAwareMessageConsumer.message(s);
            } else if (compilationProgressConsumer != null) {
                Map<String, Object> parsedMessage = externalProcessOutputParser.parse(s);
                Object[] args = (Object[]) parsedMessage.get("args");
                compilationProgressConsumer.accept(Double.valueOf(args[0].toString()).intValue());
            }
        }, "\n");
        MessageConsumerOutputStream err = new MessageConsumerOutputStream(new I18NAwareMessageConsumer(System.err), "\n");

        try {
            String dummyVidPid = "";
            callArduinoBuilder.invoke(compiler, targetBoard, targetBoard.getContainerPlatform(),
                    targetBoard.getContainerPlatform().getContainerPackage(), dummyVidPid, compileAction, out, err);
        } catch (Exception e) {
            throw new RunnerException(e);
        }
    }
}
