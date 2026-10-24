package com.serverarchitecture.academic;

import java.util.Arrays;
import java.util.List;

final class Lists {
    private Lists() {
    }

    static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
    }

    static String join(List<String> values) {
        return String.join(",", values);
    }
}
