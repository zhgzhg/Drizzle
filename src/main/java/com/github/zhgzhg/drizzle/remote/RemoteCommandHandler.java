package com.github.zhgzhg.drizzle.remote;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Carries out the commands received by {@link RemoteServer}. Results are made of JSON-friendly JDK types only:
 * String, Number, Boolean, List and Map.
 */
public interface RemoteCommandHandler {

    /**
     * Describes the IDE: versions, the selected board, the open sketches. Called on HTTP threads.
     */
    Map<String, Object> status() throws RemoteException;

    /**
     * @return whether the command name is known; checked before a job gets created
     */
    boolean supports(String command);

    /**
     * @return whether the command must not overlap with actions started in the IDE (Verify, Upload, the Drizzle
     * menus); quick, harmless commands can return false so they don't block the user
     */
    default boolean isExclusive(String command) {
        return true;
    }

    /**
     * Runs a command on the job thread. Only one command runs at a time.
     *
     * @param log receives the output to report back with the job
     * @return the job's result
     */
    Map<String, Object> execute(String command, Map<String, String> args, Consumer<String> log) throws RemoteException;
}
