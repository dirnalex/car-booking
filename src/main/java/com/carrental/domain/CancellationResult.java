package com.carrental.domain;

/**
 * Outcome of a cancellation request.
 */
public enum CancellationResult {
    CANCELLED,
    NOT_FOUND,
    ALREADY_STARTED
}
