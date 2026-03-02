Set-Location (Join-Path $PSScriptRoot "..")

# Ensure classes are compiled and run via Maven to avoid IDE classpath issues.
mvn -q -DskipTests compile
mvn -q spring-boot:run

