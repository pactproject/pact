package io.github.pactproject.app;

public record ReconciliationResult(
        boolean successful,
        Exception error
) {
    public static ReconciliationResult success()
    {
        return new ReconciliationResult(
                true,
                null
        );
    }

    public static ReconciliationResult failure(
            Exception error)
    {
        return new ReconciliationResult(
                false,
                error
        );
    }

    public boolean failed()
    {
        return !successful;
    }
}
