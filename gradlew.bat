@echo off
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
java -jar "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" %*
