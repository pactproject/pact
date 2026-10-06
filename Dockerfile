FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY . .

RUN mvn -B install

RUN set -eu; \
    mkdir -p /dist/lib /dist/plugins; \
    for module in pact-app pact-filesystem pact-kubernetes pact-ranger pact-artifact-keeper pact-postgresql pact-elasticsearch; do \
      mvn -B -f "${module}/pom.xml" \
        org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory=/dist/lib; \
    done; \
    cp pact-api/target/pact-api-*.jar /dist/lib/; \
    cp pact-core/target/pact-core-*.jar /dist/lib/; \
    cp pact-app/target/pact-app-*.jar /dist/lib/; \
    cp pact-filesystem/target/pact-filesystem-*.jar /dist/plugins/; \
    cp pact-kubernetes/target/pact-kubernetes-*.jar /dist/plugins/; \
    cp pact-ranger/target/pact-ranger-*.jar /dist/plugins/; \
    cp pact-artifact-keeper/target/pact-artifact-keeper-*.jar /dist/plugins/; \
    cp pact-postgresql/target/pact-postgresql-*.jar /dist/plugins/; \
    cp pact-elasticsearch/target/pact-elasticsearch-*.jar /dist/plugins/

FROM eclipse-temurin:21-jre

WORKDIR /opt/pact
COPY --from=build --chown=10001:10001 /dist/lib/ /opt/pact/lib/
COPY --from=build --chown=10001:10001 /dist/plugins/ /opt/pact/plugins/

USER 10001:10001

ENTRYPOINT ["java", "--enable-preview", "-cp", "/opt/pact/lib/*", "io.github.pactproject.app.Main"]
CMD ["/etc/pact/config.yaml", "/opt/pact/plugins"]
