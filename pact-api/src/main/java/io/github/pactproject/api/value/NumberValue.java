package io.github.pactproject.api.value;

import java.math.BigDecimal;

public record NumberValue(BigDecimal value) implements Value {
}
