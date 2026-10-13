package com.github.zhgzhg.drizzle.remote;

/**
 * A failure carrying one of the {@code ERR_*} codes from {@link RemoteProtocol}.
 */
public class RemoteException extends Exception {
    private final String code;

    public RemoteException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    public RemoteException(final String code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
