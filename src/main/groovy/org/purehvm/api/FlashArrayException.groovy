package org.purehvm.api

import groovy.transform.CompileStatic

/**
 * A FlashArray REST call failed. {@code status} is the HTTP status (0 for transport errors);
 * {@code errors} holds the array's {"context","message"} pairs when it returned any.
 */
@CompileStatic
class FlashArrayException extends RuntimeException {
    final int status
    final List<Map> errors

    FlashArrayException(String message, int status = 0, List<Map> errors = [], Throwable cause = null) {
        super(message, cause)
        this.status = status
        this.errors = errors ?: []
    }

    /** True when the array said the object already exists (lets create calls be idempotent). */
    boolean isAlreadyExists() {
        errors.any { Map e -> (e.message as String)?.toLowerCase()?.contains('already exists') }
    }

    /** The login works but its role is one the caller refused (checked before any token is issued). */
    @CompileStatic
    static class RoleRefusedException extends FlashArrayException {
        final String role
        RoleRefusedException(String user, String role) {
            super("${user} has role ${role}".toString())
            this.role = role
        }
    }
}
