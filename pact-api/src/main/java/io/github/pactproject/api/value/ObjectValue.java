package io.github.pactproject.api.value;

import java.util.Map;

public record ObjectValue(
        Map<String, ? extends Value> values
) implements Value {

    public ObjectValue {
        values = Map.copyOf(values);
    }
}
