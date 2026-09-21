package edu.zsc.ai.common.constant;

/**
 * Unified response codes used across the application.
 *
 * <p>Keep this class focused on numeric codes only. Message keys should live in {@link ResponseMessageKey}.</p>
 */
public class ResponseCode {

    private ResponseCode() {
        // utility class
    }

    public static final int SUCCESS = 200;
    public static final int SYSTEM_ERROR = 500;
    public static final int PARAM_ERROR = 400;
    public static final int NOT_FOUND = 404;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;
}

