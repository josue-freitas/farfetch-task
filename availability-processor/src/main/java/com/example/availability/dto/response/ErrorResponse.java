package com.example.availability.dto.response;

import java.util.List;

/**
 * Body returned for synchronous request errors (400 / 404).
 */
public record ErrorResponse(String error, List<String> details) {
}
