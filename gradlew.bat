@rem Gradle wrapper toi gian cho Windows (LUNA36 chua cai Gradle).
@rem Build tren GitHub Actions thi khong can file nay.
@rem Build local: cai Gradle 8.10 (winget install Gradle.Gradle) roi chay: gradle :app:assembleDebug
@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
  echo Chua co Gradle. Cai: winget install Gradle.Gradle
  echo Hoac day len GitHub de Actions build (xem README.md).
  exit /b 1
)
gradle %*
