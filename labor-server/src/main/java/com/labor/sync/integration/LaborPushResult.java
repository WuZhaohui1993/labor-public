package com.labor.sync.integration;

public record LaborPushResult(boolean success, String code, String message, Integer httpStatus,
                              String responseSummary) {
}
