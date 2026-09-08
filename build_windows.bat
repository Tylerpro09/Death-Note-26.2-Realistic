@echo off
where java >nul 2>&1 || (echo Java 25 no esta instalado o no esta en PATH.& exit /b 1)
where gradle >nul 2>&1 || (echo Gradle 9.x no esta instalado. Instala Gradle o ejecuta: gradle wrapper --gradle-version 9.5.0& exit /b 1)
gradle build --stacktrace
