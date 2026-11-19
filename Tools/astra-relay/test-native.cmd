@echo off
set "ASTRA_VSINSTALL="
for /f "usebackq delims=" %%V in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "ASTRA_VSINSTALL=%%V"
if not defined ASTRA_VSINSTALL exit /b 1
call "%ASTRA_VSINSTALL%\Common7\Tools\VsDevCmd.bat" -arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%
if not exist build\astra-relay-tests mkdir build\astra-relay-tests
cl.exe /nologo /std:c++17 /EHsc /O2 /UNDEBUG tests\astra-relay\native-tests.cpp /Fe:build\astra-relay-tests\native-tests.exe /Fo:build\astra-relay-tests\native-tests.obj
if errorlevel 1 exit /b %errorlevel%
build\astra-relay-tests\native-tests.exe
exit /b %errorlevel%
