package io.github.pactproject.api.exception;

public class StateProviderException extends Exception
{
    public StateProviderException(String message)
    {
        super(message);
    }

    public StateProviderException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
