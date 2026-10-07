# TDEI → R5 on-demand walkshed server.
#
# Build from the root of the r5 repo:
#   docker build -f docker/Dockerfile -t tdei-r5 .
# Run:
#   docker run -p 8000:8000 -v tdei-data:/data tdei-r5
#
# The R5 build reads its version from git, so .git stays in the build context.

########## build R5 fat jar ##########
# shadow plugin 9.x needs a recent Gradle; the repo has no wrapper, so pin one here.
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle/caches \
    gradle --no-daemon -q shadowJar \
 && cp "$(ls build/libs/*-all.jar | head -1)" /r5.jar

########## runtime ##########
FROM eclipse-temurin:21-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-aiohttp tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 r5 \
 && mkdir -p /data && chown r5 /data

COPY --from=build /r5.jar /app/r5.jar
COPY osw-tools/profiles /app/profiles
COPY docker/tdei_r5_proxy.py /app/tdei_r5_proxy.py
# the API documentation the proxy serves at /docs
COPY src/main/resources/osw-demo/docs.html src/main/resources/osw-demo/openapi.yaml /app/docs/
# host files may be owner-only (600); make /app world-readable for the non-root user
RUN chmod -R a+rX /app

ENV R5_MAIN_CLASS=com.conveyal.r5.osw.OswDemoServer \
    R5_HEAP=768m \
    JAVA_OPTS="-XX:+UseG1GC -XX:G1PeriodicGCInterval=10000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:+ExitOnOutOfMemoryError" \
    LAUNCH_CMD='exec java -Xmx$R5_HEAP $JAVA_OPTS -cp /app/r5.jar $R5_MAIN_CLASS --osw {dir} --port {port} --dataset-id {id} --profiles /app/profiles' \
    LAUNCH_CWD=/app \
    DOCS_DIR=/app/docs \
    DATA_DIR=/data \
    IDLE_TIMEOUT=1800 \
    MAX_INSTANCES=8 \
    LISTEN_PORT=8000 \
    PYTHONUNBUFFERED=1

USER r5
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/_status', timeout=4)"

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python3", "/app/tdei_r5_proxy.py"]
