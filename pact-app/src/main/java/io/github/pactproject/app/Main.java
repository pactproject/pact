package io.github.pactproject.app;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main
{
    private static final Logger log = LoggerFactory.getLogger(Main.class);

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
            log.error("PACT failed during initialization", e);
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
            log.error("PACT failed during execution", e);
            System.exit(1);
        }
    }

    private static void closeApplication(PactApplication application)
    {
        try {
            application.close();
        }
        catch (Exception e) {
            log.warn("Failed to close PACT cleanly", e);
        }
    }
}
