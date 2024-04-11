FROM nginx:1.25-bookworm

RUN \
	curl -L https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc \
	&& echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list \
	&& apt update \
    && apt install -y temurin-21-jre

COPY build/libs/LabelProxy.jar /app/LabelProxy.jar

WORKDIR /app

STOPSIGNAL SIGTERM
ENTRYPOINT []

CMD ["java", "-Xms128M", "-Xmx256M", "-XX:+UseZGC", "-jar", "/app/LabelProxy.jar"]
