package com.forwardnetworks.neo4j;

final class ForwardApiException extends RuntimeException {

    ForwardApiException(String message) {
        super(message);
    }

    ForwardApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
