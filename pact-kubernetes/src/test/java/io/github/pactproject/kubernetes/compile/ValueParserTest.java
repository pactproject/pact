package io.github.pactproject.kubernetes.compile;

import io.github.pactproject.api.value.Value;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueParserTest
{
    private final ValueParser parser =
            new ValueParser();

    @Test
    void parsesString()
    {
        assertEquals(
                Value.string("alice"),
                parser.parse("alice")
        );
    }

    @Test
    void parsesBoolean()
    {
        assertEquals(
                Value.bool(true),
                parser.parse(true)
        );
    }

    @Test
    void parsesNumber()
    {
        assertEquals(
                Value.number(
                        new BigDecimal("42.125")
                ),
                parser.parse(42.125)
        );
    }

    @Test
    void preservesNumericPrecision()
    {
        assertEquals(
                Value.number(
                        new BigDecimal("12345678901234567890.123456789")
                ),
                parser.parse(
                        new BigDecimal(
                                "12345678901234567890.123456789"
                        )
                )
        );
    }

    @Test
    void parsesNestedObjectsAndArrays()
    {
        assertEquals(
                Value.object(
                        Map.of(
                                "enabled",
                                Value.bool(true),
                                "names",
                                Value.set(
                                        Set.of(
                                                Value.string("alice"),
                                                Value.string("bob")
                                        )
                                ),
                                "nested",
                                Value.object(
                                        Map.of(
                                                "count",
                                                Value.number(
                                                        new BigDecimal("42")
                                                )
                                        )
                                )
                        )
                ),
                parser.parse(
                        Map.of(
                                "enabled",
                                true,
                                "names",
                                List.of(
                                        "alice",
                                        "bob"
                                ),
                                "nested",
                                Map.of(
                                        "count",
                                        42
                                )
                        )
                )
        );
    }

    @Test
    void deduplicatesArrayElements()
    {
        assertEquals(
                Value.set(
                        Set.of(
                                Value.string("read"),
                                Value.string("write")
                        )
                ),
                parser.parse(
                        List.of(
                                "read",
                                "read",
                                "write"
                        )
                )
        );
    }

    @Test
    void rejectsNull()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(null)
        );
    }

    @Test
    void rejectsObjectWithNonStringKey()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        Map.of(
                                42,
                                "value"
                        )
                )
        );
    }

    @Test
    void rejectsUnsupportedValue()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        new Object()
                )
        );
    }
}
