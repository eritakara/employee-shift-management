FROM tomcat:10.1-jdk21-temurin AS build

WORKDIR /app

COPY src ./src

RUN mkdir -p build/classes target/shiftflow \
    && find src/main/java -name "*.java" > sources.txt \
    && javac --release 21 -encoding UTF-8 \
      -cp "$CATALINA_HOME/lib/servlet-api.jar:src/main/webapp/WEB-INF/lib/h2-2.4.240.jar:src/main/webapp/WEB-INF/lib/postgresql-42.7.2.jar" \
      -d build/classes @sources.txt \
    && if [ -d src/main/resources ]; then cp -r src/main/resources/. build/classes/; fi \
    && cp -r src/main/webapp/. target/shiftflow/ \
    && mkdir -p target/shiftflow/WEB-INF/classes \
    && cp -r build/classes/. target/shiftflow/WEB-INF/classes/ \
    && jar --create --file target/shiftflow.war -C target/shiftflow .

FROM tomcat:10.1-jre21-temurin

ENV PORT=8080
ENV SHIFTFLOW_DATA_DIR=/opt/shiftflow/data

RUN rm -rf "$CATALINA_HOME/webapps/"* \
    && mkdir -p /opt/shiftflow/data "$CATALINA_HOME/webapps/ROOT" \
    && printf '%s\n' \
      '<!doctype html>' \
      '<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=/shiftflow/"><title>ShiftFlow</title></head>' \
      '<body><a href="/shiftflow/">ShiftFlow を開く</a></body></html>' \
      > "$CATALINA_HOME/webapps/ROOT/index.html"

COPY --from=build /app/target/shiftflow.war "$CATALINA_HOME/webapps/shiftflow.war"

EXPOSE 8080

CMD ["sh", "-c", "mkdir -p \"${SHIFTFLOW_DATA_DIR:-/opt/shiftflow/data}\" && sed -i \"s/port=\\\"8080\\\" protocol=\\\"HTTP\\/1.1\\\"/port=\\\"${PORT:-8080}\\\" protocol=\\\"HTTP\\/1.1\\\"/\" \"$CATALINA_HOME/conf/server.xml\" && sed -i \"s/port=\\\"8005\\\" shutdown=\\\"SHUTDOWN\\\"/port=\\\"-1\\\" shutdown=\\\"SHUTDOWN\\\"/\" \"$CATALINA_HOME/conf/server.xml\" && exec catalina.sh run"]
