#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('tv','mobile')][string]$Target = 'tv',
    [string]$DeviceAddress = '192.168.8.118:5555',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$RepoRoot = Split-Path $PSScriptRoot -Parent
$ToolsRoot = Join-Path $env:LOCALAPPDATA 'CinemaHall\toolchain'
$JavaRoot = Join-Path $ToolsRoot 'jdk21'
$SdkRoot = Join-Path $ToolsRoot 'android-sdk'
if (-not (Test-Path (Join-Path $JavaRoot 'bin\javac.exe'))) {
    throw 'Не найдена Java 21 в CinemaHall\toolchain\jdk21. Установи JDK 21 и укажи JAVA_HOME.'
}
if (-not (Test-Path (Join-Path $SdkRoot 'platform-tools\adb.exe'))) {
    throw 'Не найден Android SDK в CinemaHall\toolchain\android-sdk. Установи Android SDK 36.'
}
$env:JAVA_HOME = $JavaRoot
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path = "$JavaRoot\bin;$env:Path"

$GradleRoot = Join-Path $ToolsRoot 'gradle-8.13'
$Gradle = Join-Path $GradleRoot 'bin\gradle.bat'
if (-not (Test-Path $Gradle)) {
    New-Item -ItemType Directory -Force $ToolsRoot | Out-Null
    $Zip = Join-Path $ToolsRoot 'gradle-8.13-bin.zip'
    $Hash = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    if (-not (Test-Path $Zip) -or (Get-FileHash $Zip -Algorithm SHA256).Hash -ne $Hash) {
        Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile $Zip
    }
    if ((Get-FileHash $Zip -Algorithm SHA256).Hash -ne $Hash) { throw 'Gradle SHA256 mismatch' }
    Expand-Archive -LiteralPath $Zip -DestinationPath $ToolsRoot -Force
}
$SdkManager = Join-Path $SdkRoot 'cmdline-tools\23.0\bin\sdkmanager.bat'
if (Test-Path $SdkManager) {
    & $SdkManager "--sdk_root=$SdkRoot" 'platforms;android-36' 'build-tools;36.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось установить SDK 36' }
}
$Engines = @(
    @{ Abi='arm64-v8a'; Name='TorrServer-android-arm64'; Hash='93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5' },
    @{ Abi='armeabi-v7a'; Name='TorrServer-android-arm7'; Hash='e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6' }
)
foreach ($Engine in $Engines) {
    $Directory = Join-Path $RepoRoot ('android\core\app\src\main\jniLibs\' + $Engine.Abi)
    New-Item -ItemType Directory -Force $Directory | Out-Null
    $Binary = Join-Path $Directory 'libtorrserver.so'
    if (-not (Test-Path $Binary) -or (Get-FileHash $Binary -Algorithm SHA256).Hash -ne $Engine.Hash) {
        Invoke-WebRequest -UseBasicParsing ('https://github.com/YouROK/TorrServer/releases/download/MatriX.147.1/' + $Engine.Name) -OutFile $Binary
    }
    if ((Get-FileHash $Binary -Algorithm SHA256).Hash -ne $Engine.Hash) { throw 'TorrServer SHA256 mismatch' }
}
$ProjectRoot = Join-Path $RepoRoot 'android\cinemahall'
$Task = ':app:assemble' + (Get-Culture).TextInfo.ToTitleCase($Target) + 'Debug'
& $Gradle -p $ProjectRoot $Task '--no-daemon' '--max-workers=2'
if ($LASTEXITCODE -ne 0) { throw 'CinemaHall Next build failed' }
$Output = Join-Path $ProjectRoot ("app\build\outputs\apk\$Target\debug")
$Apk = Get-ChildItem $Output -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Apk) { throw 'APK not found' }
Write-Host "APK: $($Apk.FullName)" -ForegroundColor Green
if ($BuildOnly) { return }
$Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if ($DeviceAddress.Contains(':')) { & $Adb connect $DeviceAddress | Out-Host }
$Abi = (& $Adb -s $DeviceAddress shell getprop ro.product.cpu.abilist) -join ''
if ($LASTEXITCODE -ne 0 -or $Abi -notmatch 'arm64-v8a|armeabi-v7a') {
    throw "Устройство недоступно или ABI не поддерживается: $Abi"
}
& $Adb -s $DeviceAddress install -r $Apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'Установка APK не удалась' }
$Package = 'app.cinemahall.next.' + $Target
& $Adb -s $DeviceAddress shell monkey -p $Package -c android.intent.category.LAUNCHER 1
if ($LASTEXITCODE -ne 0) { throw 'Не удалось запустить CinemaHall Next' }
Write-Host 'CinemaHall Next установлен рядом с текущим CinemaHall.' -ForegroundColor Green
