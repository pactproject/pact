package io.github.pactproject.kubernetes.compile;

import io.github.pactproject.api.value.Value;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ValueParser
{
    Value parse(Object value)
    {
        if (value instanceof String string) {
            return Value.string(string);
        }

        if (value instanceof Boolean bool) {
            return Value.bool(bool);
        }

        if (value instanceof Number number) {
            return Value.number(
                    new BigDecimal(number.toString())
            );
        }

        if (value instanceof Map<?, ?> map) {
            return parseObject(map);
        }

        if (value instanceof Iterable<?> iterable) {
            return parseArray(iterable);
        }

        throw new IllegalArgumentException(
                "Unsupported Kubernetes value: " + value
        );
    }

    private Value parseObject(Map<?, ?> map)
    {
        Map<String, Value> result = new HashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Object key must be a string"
                );
            }

            result.put(
                    key,
                    parse(entry.getValue())
            );
        }

        return Value.object(result);
    }

    private Value parseArray(Iterable<?> iterable)
    {
        Set<Value> result = new HashSet<>();

        for (Object element : iterable) {
            result.add(parse(element));
        }

        return Value.set(result);
    }
}
