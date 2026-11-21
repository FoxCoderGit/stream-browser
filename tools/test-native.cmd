@echo off
setlocal
if not defined JAVA_HOME exit /b 2
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -property installationPath`) do set "STREAM_VS=%%i"
call "%STREAM_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist "%~dp0..\build\native-host" mkdir "%~dp0..\build\native-host"
pushd "%~dp0..\build\native-host"
cl /nologo /std:c++20 /EHsc /LD /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" "%~dp0..\app\src\main\cpp\core.cpp" /link /OUT:stream_core.dll
set "STREAM_EXIT=%ERRORLEVEL%"
popd
exit /b %STREAM_EXIT%
