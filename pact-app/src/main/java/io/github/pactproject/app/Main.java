package io.github.pactproject.app;

import java.nio.file.Path;

public final class Main
{
    private Main()
    {
    }

    public static void main(String[] args)
    {
        if (args.length != 2) {
            System.err.println(
                    "Usage: pact-app <config> <plugins-directory>"
            );
            System.exit(2);
        }

        Path configPath = Path.of(args[0]);
        Path pluginsDirectory = Path.of(args[1]);

        try (PactApplication application =
                     PactApplication.create(
                             configPath,
                             pluginsDirectory
                     )) {

            application.run();
        }
        catch (Exception e) {
            System.err.println(
                    "PACT failed: " + e.getMessage()
            );

            e.printStackTrace(System.err);

            System.exit(1);
        }
    }
}
