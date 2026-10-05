package io.github.pactproject.postgresql.model;

public record RoutineSignature(String name, String arguments) {
    public RoutineSignature {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine name must not be blank"
            );
        }
        if (arguments == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine target must include argument types"
            );
        }
    }

    public static RoutineSignature parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine target must include argument types"
            );
        }
        int opening = openingParenthesis(value);
        if (opening <= 0 || !value.endsWith(")")) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine target must use name(type, ...)"
            );
        }
        String name = parseIdentifier(value.substring(0, opening).trim());
        String arguments = value.substring(opening + 1, value.length() - 1).trim();
        return new RoutineSignature(name, arguments);
    }

    public String display() {
        return name + "(" + arguments + ")";
    }

    private static int openingParenthesis(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < value.length()
                        && value.charAt(i + 1) == '"') {
                    i++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (current == '(' && !quoted) {
                return i;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException(
                    "Unterminated quoted PostgreSQL routine name"
            );
        }
        return -1;
    }

    private static String parseIdentifier(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")
                && value.length() >= 2) {
            StringBuilder result = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char current = value.charAt(i);
                if (current == '"') {
                    if (i + 1 < value.length() - 1
                            && value.charAt(i + 1) == '"') {
                        result.append('"');
                        i++;
                    }
                    else {
                        throw new IllegalArgumentException(
                                "Invalid quoted PostgreSQL routine name"
                        );
                    }
                }
                else {
                    result.append(current);
                }
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException(
                        "PostgreSQL routine name must not be empty"
                );
            }
            return result.toString();
        }
        if (!value.matches("[\\p{L}_][\\p{L}\\p{N}_$]*")) {
            throw new IllegalArgumentException(
                    "Invalid PostgreSQL routine name: " + value
            );
        }
        return value;
    }
}
