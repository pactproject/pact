package io.github.pactproject.app.config;

public final class PactConfigException
        extends Exception
{
    public PactConfigException(
            String message)
    {
        super(message);
    }

    public PactConfigException(
            String message,
            Throwable cause)
    {
        super(message, cause);
    }
}
