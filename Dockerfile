FROM eclipse-temurin:24-jdk
WORKDIR /app
COPY src ./src
RUN mkdir -p out && javac --add-modules jdk.httpserver -d out $(find src/main/java -name '*.java')
EXPOSE 8080
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out", "com.coopcompass.ApplicationServer"]
