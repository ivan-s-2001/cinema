#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$TvAddress = '192.168.8.118:5555',
    [switch]$BuildOnly,
    [switch]$AcceptReinstall
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$RepoRoot = Split-Path $PSScriptRoot -Parent
$ToolsRoot = Join-Path $env:LOCALAPPDATA 'CinemaHall\toolchain'
New-Item -ItemType Directory -Force $ToolsRoot | Out-Null

function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Команда завершилась с ошибкой $LASTEXITCODE : $Command" }
}
function Download-Verified([string]$Url, [string]$Destination, [string]$Checksum, [string]$Algorithm = 'SHA256') {
    if ((Test-Path $Destination) -and ((Get-FileHash $Destination -Algorithm $Algorithm).Hash -eq $Checksum)) { return }
    $part = "$Destination.part"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $part -TimeoutSec 600
        if ((Get-FileHash $part -Algorithm $Algorithm).Hash -ne $Checksum) { throw "Не совпала контрольная сумма: $Destination" }
        Move-Item -Force $part $Destination
    } finally { if (Test-Path $part) { Remove-Item -Force $part } }
}

Write-Host '[1/6] Java 17' -ForegroundColor Cyan
$JavaRoot = Join-Path $ToolsRoot 'jdk17'
if (-not (Test-Path "$JavaRoot\bin\javac.exe")) {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = @($assets)[0].binary.package
    if (-not $package.checksum -or -not $package.link) { throw 'Adoptium не вернул JDK 17.' }
    $archive = Join-Path $ToolsRoot 'jdk17.zip'
    Download-Verified $package.link $archive $package.checksum
    $stage = Join-Path $ToolsRoot 'jdk-unpack'
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    Expand-Archive -LiteralPath $archive -DestinationPath $stage -Force
    $jdk = Get-ChildItem $stage -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -First 1
    if (-not $jdk) { throw 'В архиве нет JDK.' }
    if (Test-Path $JavaRoot) { Remove-Item -Recurse -Force $JavaRoot }
    Move-Item $jdk.FullName $JavaRoot
    Remove-Item -Recurse -Force $stage
}
$env:JAVA_HOME = $JavaRoot
$env:Path = "$JavaRoot\bin;$env:Path"
Invoke-Checked { & "$JavaRoot\bin\javac.exe" -version }

Write-Host '[2/6] Android SDK; принятие лицензий SDK для сборки' -ForegroundColor Cyan
$SdkRoot = Join-Path $ToolsRoot 'android-sdk'
$SdkManager = Join-Path $SdkRoot 'cmdline-tools\12.0\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    # Fixed version, compatible with JDK 17. Obtain its checksum from Google's repository metadata.
    [xml]$index = (Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/repository2-1.xml' -TimeoutSec 120).Content
    $toolPackage = $index.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;12.0']")
    if (-not $toolPackage) { throw 'В официальном индексе Google нет cmdline-tools 12.0.' }
    $win = $toolPackage.SelectSingleNode("archives/archive[host-os='windows']/complete")
    if (-not $win) { throw 'В индексе Google нет Windows-архива cmdline-tools 12.0.' }
    $url = 'https://dl.google.com/android/repository/' + $win.SelectSingleNode('url').InnerText
    $zip = Join-Path $ToolsRoot 'android-commandlinetools-12.zip'
    Download-Verified $url $zip $win.SelectSingleNode('checksum').InnerText 'SHA1'
    $stage = Join-Path $ToolsRoot 'sdk-unpack'
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    Expand-Archive -LiteralPath $zip -DestinationPath $stage -Force
    $target = Join-Path $SdkRoot 'cmdline-tools\12.0'
    New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item (Join-Path $stage 'cmdline-tools') $target
    Remove-Item -Recurse -Force $stage
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
Invoke-Checked { 1..100 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$SdkRoot" --licenses }
Invoke-Checked { & $SdkManager "--sdk_root=$SdkRoot" 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0' }
('sdk.dir=' + $SdkRoot.Replace('\','/')) | Set-Content -Encoding ASCII (Join-Path $RepoRoot 'local.properties')

Write-Host '[3/6] Встроенный TorrServer, проверка SHA256' -ForegroundColor Cyan
$engines = @(
    @{ Abi='arm64-v8a'; Name='TorrServer-android-arm64'; Hash='93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5' },
    @{ Abi='armeabi-v7a'; Name='TorrServer-android-arm7'; Hash='e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6' }
)
foreach ($engine in $engines) {
    $dir = Join-Path $RepoRoot ('app\src\main\jniLibs\' + $engine.Abi)
    New-Item -ItemType Directory -Force $dir | Out-Null
    Download-Verified ('https://github.com/YouROK/TorrServer/releases/download/MatriX.147.1/' + $engine.Name) (Join-Path $dir 'libtorrserver.so') $engine.Hash
}

Write-Host '[4/6] Проверка логики, сборка APK и Android lint' -ForegroundColor Cyan
Push-Location $RepoRoot
try {
    New-Item -ItemType Directory -Force 'build\check' | Out-Null
    Invoke-Checked { & "$JavaRoot\bin\javac.exe" -encoding UTF-8 -d 'build\check' 'app\src\main\java\app\cinemahall\tv\Logic.java' 'tests\LogicCheck.java' }
    Invoke-Checked { & "$JavaRoot\bin\java.exe" -cp 'build\check' 'app.cinemahall.tv.LogicCheck' }
    Invoke-Checked { & '.\gradlew.bat' --no-daemon 'clean' ':app:assembleDebug' ':app:lintDebug' }
} finally { Pop-Location }
$Apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $Apk)) { throw "APK не найден: $Apk" }
if ($BuildOnly) { Write-Host "APK готов: $Apk" -ForegroundColor Green; return }

Write-Host "[5/6] Подключение к ТВ $TvAddress. Разрешите отладку на экране телевизора." -ForegroundColor Cyan
$Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
Invoke-Checked { & $Adb start-server }
$ErrorActionPreference = 'Continue'
try { $connect = & $Adb connect $TvAddress 2>&1 } finally { $ErrorActionPreference = 'Stop' }
$connect | ForEach-Object { Write-Host $_ }
if ($connect -match 'cannot connect|failed to connect|cannot resolve|refused') { throw 'ТВ недоступен. Проверьте IP, общую сеть и включённую сетевую отладку.' }
$ready = $false
for ($attempt=0; $attempt -lt 45; $attempt++) {
    $state = (& $Adb devices) -join "`n"
    if ($state -match ('(?m)^' + [regex]::Escape($TvAddress) + '\s+device\s*$')) { $ready=$true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'ТВ не авторизовал ADB за 90 секунд. Подтвердите разрешение на ТВ и запустите скрипт повторно.' }

Write-Host '[6/6] Установка и запуск CinemaHall' -ForegroundColor Cyan
$ErrorActionPreference = 'Continue'
try { $install = & $Adb -s $TvAddress install -r $Apk 2>&1; $installCode = $LASTEXITCODE } finally { $ErrorActionPreference = 'Stop' }
$install | ForEach-Object { Write-Host $_ }
if ($installCode -ne 0 -and ($install -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE')) {
    # A downloaded APK and the first local build normally have different debug keys.
    # Never silently remove the installed app or its history.
    $allow = $AcceptReinstall
    if (-not $allow) {
        Write-Host 'На ТВ установлена версия с другой подписью. Для первой локальной сборки нужно удалить её. Локальная история и избранное будут потеряны.' -ForegroundColor Yellow
        $answer = Read-Host 'Введите ДА, чтобы переустановить; Enter отменит установку'
        $allow = ($answer -eq 'ДА' -or $answer -eq 'YES')
    }
    if (-not $allow) { throw "Установка отменена. Собранный APK сохранён: $Apk" }
    Invoke-Checked { & $Adb -s $TvAddress uninstall 'app.cinemahall.tv' }
    Invoke-Checked { & $Adb -s $TvAddress install $Apk }
} elseif ($installCode -ne 0) { throw "Ошибка установки: $($install -join ' ')" }
Invoke-Checked { & $Adb -s $TvAddress shell am start -n 'app.cinemahall.tv/.MainActivity' }
Write-Host "Готово. CinemaHall установлен и запущен на $TvAddress" -ForegroundColor Green
Write-Host "Следующий запуск обновит приложение с сохранением данных. APK: $Apk"
