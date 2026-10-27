#Requires -Version 5.1
[CmdletBinding()]
param(
    [Alias('DeviceAddress')][string]$TvAddress = '192.168.8.118:5555',
    [ValidateSet('tv','mobile','both')][string]$Target = 'tv',
    [switch]$BuildOnly
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
function Get-RemoteText([string]$Url) {
    $content = (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 120).Content
    if ($content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($content) }
    return [string]$content
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

Write-Host '[1/6] Java 21' -ForegroundColor Cyan
$JavaRoot = Join-Path $ToolsRoot 'jdk21'
if (-not (Test-Path "$JavaRoot\bin\javac.exe")) {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = @($assets)[0].binary.package
    if (-not $package.checksum -or -not $package.link) { throw 'Adoptium не вернул JDK 21.' }
    $archive = Join-Path $ToolsRoot 'jdk21.zip'
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
$SdkManager = Join-Path $SdkRoot 'cmdline-tools\23.0\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    # Fixed version, compatible with JDK 21. Obtain its checksum from Google's repository metadata.
    [xml]$index = Get-RemoteText 'https://dl.google.com/android/repository/repository2-3.xml'
    $toolPackage = $index.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;23.0']")
    if (-not $toolPackage) { throw 'В официальном индексе Google нет cmdline-tools 23.0.' }
    $win = $toolPackage.SelectSingleNode("archives/archive[host-os='windows']/complete")
    if (-not $win) { throw 'В индексе Google нет Windows-архива cmdline-tools 23.0.' }
    $url = 'https://dl.google.com/android/repository/' + $win.SelectSingleNode('url').InnerText
    $zip = Join-Path $ToolsRoot 'android-commandlinetools-23.zip'
    Download-Verified $url $zip $win.SelectSingleNode('checksum').InnerText 'SHA1'
    $stage = Join-Path $ToolsRoot 'sdk-unpack'
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    Expand-Archive -LiteralPath $zip -DestinationPath $stage -Force
    $target = Join-Path $SdkRoot 'cmdline-tools\23.0'
    New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item (Join-Path $stage 'cmdline-tools') $target
    Remove-Item -Recurse -Force $stage
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
Invoke-Checked { 1..100 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$SdkRoot" --licenses }
Invoke-Checked { & $SdkManager "--sdk_root=$SdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' }
if ($Target -ne 'tv') {
    Invoke-Checked { & $SdkManager "--sdk_root=$SdkRoot" 'platforms;android-37.1' }
}
$BuildTargets = if ($Target -eq 'both') { @('tv','mobile') } else { @($Target) }
foreach ($BuildTarget in $BuildTargets) {
    $ProjectName = if ($BuildTarget -eq 'tv') { 'nuvio-tv' } else { 'nuvio-mobile' }
    $AppRoot = Join-Path $RepoRoot ('android\' + $ProjectName)
    Write-Host "Preparing $ProjectName" -ForegroundColor Cyan
    Invoke-Checked { git -C $RepoRoot submodule update --init --depth 1 -- ('android/' + $ProjectName) }
    $Patch = Join-Path $RepoRoot ('android\' + $ProjectName + '.patch')
    $ErrorActionPreference = 'Continue'
    try {
        git -C $AppRoot apply --reverse --check $Patch 2>$null
        $AlreadyApplied = ($LASTEXITCODE -eq 0)
        git -C $AppRoot apply --check $Patch 2>$null
        $CanApply = ($LASTEXITCODE -eq 0)
    } finally { $ErrorActionPreference = 'Stop' }
    if (-not $AlreadyApplied) {
        if (-not $CanApply) { throw "$ProjectName contains local changes. They were preserved; the integration patch could not be applied." }
        Invoke-Checked { git -C $AppRoot apply $Patch }
    }
    $PropertiesPath = Join-Path $AppRoot 'local.properties'
    $ExistingProperties = if (Test-Path $PropertiesPath) { @(Get-Content -LiteralPath $PropertiesPath) } else { @() }
    $Properties = @($ExistingProperties | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' }) + ('sdk.dir=' + $SdkRoot.Replace('\','/'))
    [IO.File]::WriteAllLines($PropertiesPath, [string[]]$Properties, (New-Object Text.UTF8Encoding($false)))
}

Write-Host '[3/6] Встроенный TorrServer, проверка SHA256' -ForegroundColor Cyan
$engines = @(
    @{ Abi='arm64-v8a'; Name='TorrServer-android-arm64'; Hash='93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5' }, @{ Abi='armeabi-v7a'; Name='TorrServer-android-arm7'; Hash='e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6' }
)
foreach ($engine in $engines) {
    $dir = Join-Path $RepoRoot ('android\core\app\src\main\jniLibs\' + $engine.Abi)
    New-Item -ItemType Directory -Force $dir | Out-Null
    Download-Verified ('https://github.com/YouROK/TorrServer/releases/download/MatriX.147.1/' + $engine.Name) (Join-Path $dir 'libtorrserver.so') $engine.Hash
}

Write-Host '[4/6] Build CinemaHall on Nuvio' -ForegroundColor Cyan
$BuiltApks = @()
foreach ($BuildTarget in $BuildTargets) {
    $ProjectName = if ($BuildTarget -eq 'tv') { 'nuvio-tv' } else { 'nuvio-mobile' }
    $ModuleName = if ($BuildTarget -eq 'tv') { 'app' } else { 'androidApp' }
    $AppRoot = Join-Path $RepoRoot ('android\' + $ProjectName)
    Push-Location $AppRoot
    try {
        Invoke-Checked { & '.\gradlew.bat' --no-daemon '--max-workers=2' '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' (':' + $ModuleName + ':assembleFullDebug') }
    } finally { Pop-Location }
    $Output = Join-Path $AppRoot ($ModuleName + '\build\outputs\apk\full\debug')
    $ApkFile = Get-ChildItem -LiteralPath $Output -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $ApkFile) { throw "APK not found: $Output" }
    $BuiltApks += @{ Path=$ApkFile.FullName; Package=('app.cinemahall.' + $BuildTarget) }
    Write-Host ("APK: " + $ApkFile.FullName) -ForegroundColor Green
}
if ($BuildOnly) { return }

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

Write-Host '[6/6] Install and launch CinemaHall' -ForegroundColor Cyan
foreach ($BuiltApk in $BuiltApks) {
    $Apk = $BuiltApk.Path
    Invoke-Checked { & $Adb -s $TvAddress install -r $Apk }
    # Resolve each project's real launcher rather than hard-coding upstream Activity names.
    Invoke-Checked { & $Adb -s $TvAddress shell monkey -p $BuiltApk.Package -c android.intent.category.LAUNCHER 1 }
}
Write-Host "CinemaHall installed. Existing upstream apps were preserved." -ForegroundColor Green
