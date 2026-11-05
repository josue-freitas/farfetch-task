package com.example.availability.exception;

import java.util.List;

/**
 * Thrown when an event, a CSV upload or a CSV row does not satisfy the input contract.
 */
public class InvalidInputException extends RuntimeException {

    private final List<String> errors;

    public InvalidInputException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
