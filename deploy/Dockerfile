FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN adduser -D -u 10001 chat
COPY app.jar /app/app.jar
USER chat
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Xms64m -Xmx192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]