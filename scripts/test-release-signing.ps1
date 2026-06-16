param(
    [Parameter(Mandatory = $true)]
    [string]$KeystoreFile,

    [string]$Alias = "key0"
)

$ErrorActionPreference = "Stop"

function ConvertFrom-SecureStringPlain {
    param([securestring]$SecureValue)

    if ($null -eq $SecureValue -or $SecureValue.Length -eq 0) {
        return ""
    }

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

$resolvedKeystore = Resolve-Path -LiteralPath $KeystoreFile

$storePasswordSecure = Read-Host "Keystore password" -AsSecureString
$keyPasswordSecure = Read-Host "Key password for alias '$Alias' (press Enter to reuse keystore password)" -AsSecureString

$storePassword = ConvertFrom-SecureStringPlain $storePasswordSecure
$keyPassword = ConvertFrom-SecureStringPlain $keyPasswordSecure
if ([string]::IsNullOrEmpty($keyPassword)) {
    $keyPassword = $storePassword
}

$oldEnv = @{
    ANDROID_KEYSTORE_FILE = $env:ANDROID_KEYSTORE_FILE
    ANDROID_KEYSTORE_PASSWORD = $env:ANDROID_KEYSTORE_PASSWORD
    ANDROID_KEY_ALIAS = $env:ANDROID_KEY_ALIAS
    ANDROID_KEY_PASSWORD = $env:ANDROID_KEY_PASSWORD
    JAVA_HOME = $env:JAVA_HOME
    PATH = $env:PATH
}

try {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeCandidates = @(
            "$env:ProgramFiles\Android\Android Studio\jbr",
            "${env:ProgramFiles(x86)}\Android\Android Studio\jbr"
        )

        foreach ($candidate in $javaHomeCandidates) {
            if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe"))) {
                $env:JAVA_HOME = $candidate
                $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
                Write-Host "Using Java from: $env:JAVA_HOME"
                break
            }
        }
    }

    $env:ANDROID_KEYSTORE_FILE = $resolvedKeystore.Path
    $env:ANDROID_KEYSTORE_PASSWORD = $storePassword
    $env:ANDROID_KEY_ALIAS = $Alias
    $env:ANDROID_KEY_PASSWORD = $keyPassword

    $gradlew = if ($IsWindows -or $env:OS -eq "Windows_NT") { ".\gradlew.bat" } else { "./gradlew" }
    & $gradlew --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Signed release APK build passed."
    Write-Host "APK output: build/outputs/apk/release/"
}
finally {
    foreach ($name in $oldEnv.Keys) {
        if ($null -eq $oldEnv[$name]) {
            Remove-Item "Env:\$name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:\$name" $oldEnv[$name]
        }
    }
}
