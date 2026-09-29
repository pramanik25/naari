package com.example.naarishakti.cloud;

import androidx.annotation.Nullable;

/**
 * Typed failures of an API call. {@link #isTransient()} tells queues whether to retry later
 * (network down, 5xx, 408, 429, auth being repaired) or give up on the request (other 4xx).
 */
public class CloudException extends Exception {

    /** HTTP status, or 0 when no response was received. */
    public final int status;
    /** Machine code from the server's {@code {"error": ...}} body, or a local code. */
    @Nullable public final String code;

    CloudException(String message, @Nullable Throwable cause, int status, @Nullable String code) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** True when retrying the same request later may succeed. */
    public boolean isTransient() {
        return false;
    }

    /** Cloud features are off (no API_BASE_URL, or the user deleted her cloud data). */
    public static final class Disabled extends CloudException {
        Disabled(String message) {
            super(message, null, 0, "disabled");
        }
    }

    /** No HTTP response: offline, DNS, TLS, timeout, connection reset. */
    public static final class Network extends CloudException {
        Network(Throwable cause) {
            super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
                    cause, 0, "network");
        }

        @Override
        public boolean isTransient() {
            return true;
        }
    }

    /** The server answered with a non-2xx status. */
    public static class Http extends CloudException {
        Http(int status, @Nullable String code, String message) {
            super(message, null, status, code);
        }

        @Override
        public boolean isTransient() {
            return status >= 500 || status == 408 || status == 429;
        }
    }

    /** 401 even after re-registering once. Retried later (the token will be repaired again). */
    public static final class Unauthorized extends Http {
        Unauthorized(@Nullable String code, String message) {
            super(401, code, message);
        }

        @Override
        public boolean isTransient() {
            return true;
        }
    }

    /** A 2xx response without the fields the contract promises. */
    public static final class BadResponse extends CloudException {
        BadResponse(String message) {
            super(message, null, 0, "bad_response");
        }
    }
}
