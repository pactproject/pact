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

        PactApplication application;
        try {
            application = PactApplication.create(
                    configPath,
                    pluginsDirectory
            );
        }
        catch (Exception e) {
            System.err.println(
                    "PACT failed: " + e.getMessage()
            );
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        () -> closeApplication(application),
                        "pact-shutdown"
                )
        );
        try (application) {
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

    private static void closeApplication(PactApplication application)
    {
        try {
            application.close();
        }
        catch (Exception e) {
            System.err.println(
                    "Failed to close PACT cleanly: " + e.getMessage()
            );
        }
    }
}
