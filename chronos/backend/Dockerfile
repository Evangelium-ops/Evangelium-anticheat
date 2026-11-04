FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/backend/ /app/
RUN chmod +x /app/bin/backend
EXPOSE 9443 9080
ENTRYPOINT ["/app/bin/backend"]
