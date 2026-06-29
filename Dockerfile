FROM tomcat:10.1-jdk21-temurin AS build

WORKDIR /app

COPY dokoTsubu-master/src ./src

RUN apt-get update && apt-get install -y curl \
    && mkdir -p src/main/webapp/WEB-INF/lib \
    && curl -f -sSLo src/main/webapp/WEB-INF/lib/postgresql-42.7.3.jar https://jdbc.postgresql.org/download/postgresql-42.7.3.jar \
    && curl -f -sSLo src/main/webapp/WEB-INF/lib/HikariCP-5.1.0.jar https://repo1.maven.org/maven2/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar \
    && curl -f -sSLo src/main/webapp/WEB-INF/lib/slf4j-api-2.0.12.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.12/slf4j-api-2.0.12.jar \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* \
    && mkdir -p build/classes target/shiftflow \
    && find src/main/java -name "*.java" > sources.txt \
    && javac --release 21 -encoding UTF-8 \
      -cp "$CATALINA_HOME/lib/servlet-api.jar:src/main/webapp/WEB-INF/lib/*" \
      -d build/classes @sources.txt \
    && if [ -d src/main/resources ]; then cp -r src/main/resources/. build/classes/; fi \
    && cp -r src/main/webapp/. target/shiftflow/ \
    && mkdir -p target/shiftflow/WEB-INF/classes \
    && cp -r build/classes/. target/shiftflow/WEB-INF/classes/ \
    && jar --create --file target/ROOT.war -C target/shiftflow .

FROM tomcat:10.1-jre21-temurin

ENV PORT=10000
ENV SHIFTFLOW_DATA_DIR=/opt/shiftflow/data

RUN rm -rf "$CATALINA_HOME/webapps/"* \
    && mkdir -p /opt/shiftflow/data

COPY --from=build /app/target/ROOT.war "$CATALINA_HOME/webapps/ROOT.war"

EXPOSE 10000

# Render無料プランなどのコンテナ実行環境に適合させるため、以下のコンフィグ調整を行ってからTomcatを起動します:
# 1. 待ち受けポートを Render がデフォルトでヘルスチェックする 10000番ポート (${PORT}) に変更。
# 2. 不要なシャットダウンポート (8005) を完全に無効化 (-1) して、ヘルスチェック接続時における誤動作やポート衝突を防止。
CMD ["sh", "-c", "mkdir -p \"${SHIFTFLOW_DATA_DIR:-/opt/shiftflow/data}\" && sed -i \"s/port=\\\"8080\\\" protocol=\\\"HTTP\\/1.1\\\"/port=\\\"${PORT:-10000}\\\" protocol=\\\"HTTP\\/1.1\\\"/\" \"$CATALINA_HOME/conf/server.xml\" && sed -i \"s/port=\\\"8005\\\" shutdown=\\\"SHUTDOWN\\\"/port=\\\"-1\\\" shutdown=\\\"SHUTDOWN\\\"/\" \"$CATALINA_HOME/conf/server.xml\" && exec catalina.sh run"]
