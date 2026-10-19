package org.torproject.onionmasq.errors;

public class RustPanicException extends OnionmasqException {
    public RustPanicException(String message) {
        super(message);
    }
}
