@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\Tools\VsDevCmd.bat" -arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /std:c++17 /EHsc /O2 /UNDEBUG /I TMessagesProj\jni\tgnet Tools\tests\ghost87\SessionCodecTest.cpp /Fe:Tools\tests\ghost87\session-codec-host.exe /Fo:Tools\tests\ghost87\session-codec-host.obj
if errorlevel 1 exit /b %errorlevel%
Tools\tests\ghost87\session-codec-host.exe Tools\tests\ghost87\native-auth.fixture
exit /b %errorlevel%
