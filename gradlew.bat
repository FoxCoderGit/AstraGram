@echo off
setlocal
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    java.exe -Dfile.encoding=UTF-8 -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
exit /b %ERRORLEVEL%
