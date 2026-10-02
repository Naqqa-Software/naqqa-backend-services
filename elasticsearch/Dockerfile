FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY src/main/java ./src/main/java
RUN find src/main/java -name '*.java' > sources.txt \
    && mkdir -p classes \
    && javac --add-modules jdk.incubator.vector -encoding UTF-8 -nowarn -Xlint:none -d classes @sources.txt

FROM eclipse-temurin:21-jdk-jammy
WORKDIR /usr/share/naqqa-elasticsearch
COPY --from=build /src/classes ./classes
RUN mkdir -p data logs
ENV ES_JAVA_OPTS="-Xms256m -Xmx1g"
EXPOSE 9200 9300
VOLUME ["/usr/share/naqqa-elasticsearch/data"]
ENTRYPOINT ["sh", "-c", "exec java --add-modules jdk.incubator.vector $ES_JAVA_OPTS -cp classes com.naqqa.elasticsearch.node.Bootstrap -Epath.data=/usr/share/naqqa-elasticsearch/data -Epath.logs=/usr/share/naqqa-elasticsearch/logs -Enetwork.host=0.0.0.0 -Ehttp.port=9200 -Etransport.port=9300 -Ediscovery.type=single-node \"$@\"", "--"]
