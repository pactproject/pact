package io.github.pactproject.app.plugin;

public final class PluginLoadException
        extends Exception
{
    public PluginLoadException(
            String message,
            Throwable cause)
    {
        super(message, cause);
    }
}
