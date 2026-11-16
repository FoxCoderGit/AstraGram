@echo off
set "ASTRA_VSINSTALL="
for /f "usebackq delims=" %%V in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "ASTRA_VSINSTALL=%%V"
if not defined ASTRA_VSINSTALL (
    echo Install Visual Studio C++ Build Tools to run the native fixture tests.
    exit /b 1
)
call "%ASTRA_VSINSTALL%\Common7\Tools\VsDevCmd.bat" -arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /std:c++17 /EHsc /O2 /UNDEBUG /I TMessagesProj\jni\tgnet Tools\tests\ghost87\SessionCodecTest.cpp /Fe:Tools\tests\ghost87\session-codec-host.exe /Fo:Tools\tests\ghost87\session-codec-host.obj
if errorlevel 1 exit /b %errorlevel%
Tools\tests\ghost87\session-codec-host.exe Tools\tests\ghost87\native-auth.fixture
exit /b %errorlevel%
