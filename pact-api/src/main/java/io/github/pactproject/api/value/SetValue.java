package io.github.pactproject.api.value;

import java.util.Set;

public record SetValue(
        Set<? extends Value> values
) implements Value {

    public SetValue {
        values = Set.copyOf(values);
    }
}
