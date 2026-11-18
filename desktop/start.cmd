@echo off
setlocal
cd /d "%~dp0" || exit /b 1
if not exist ".venv\Scripts\python.exe" (
  echo Set up Python 3.12 and .venv as described in desktop\README.md.
  pause
  exit /b 2
)
".venv\Scripts\python.exe" -B -X utf8 run.py --browser %*
if errorlevel 1 (
  pause
  exit /b 1
)
