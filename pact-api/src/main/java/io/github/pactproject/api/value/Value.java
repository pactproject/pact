package io.github.pactproject.api.value;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public sealed interface Value
        permits StringValue,
        NumberValue,
        BooleanValue,
        SetValue,
        ObjectValue {

    static StringValue string(String value) {
        return new StringValue(value);
    }

    static NumberValue number(BigDecimal value) {
        return new NumberValue(value);
    }

    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    static SetValue set(Set<? extends Value> values) {
        return new SetValue(values);
    }

    static ObjectValue object(Map<String, ? extends Value> values) {
        return new ObjectValue(values);
    }
}
