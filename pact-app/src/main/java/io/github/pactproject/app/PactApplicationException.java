package io.github.pactproject.app;

public final class PactApplicationException
        extends Exception
{
    public PactApplicationException(
            String message,
            Throwable cause)
    {
        super(message, cause);
    }
}
