FROM maven:3.9-eclipse-temurin-21

WORKDIR /app

# Build context is the whole backend/ reactor (this module's pom.xml inherits
# from ../pom.xml, so the parent + sibling modules must be present to resolve).
COPY . .

EXPOSE 8081

CMD ["mvn", "-f", "auth-service/pom.xml", "spring-boot:run"]
