[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SigningDirectory,

    [string]$Repository = "YGHFv/NeriPlayer"
)

$ErrorActionPreference = "Stop"

$signingRoot = (Resolve-Path -LiteralPath $SigningDirectory).Path
$base64Path = Join-Path $signingRoot "reamicro-release-base64.txt"
$secretsPath = Join-Path $signingRoot "reamicro-release-secrets.txt"
$keystorePath = Join-Path $signingRoot "reamicro-release.jks"

foreach ($path in @($base64Path, $secretsPath, $keystorePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required signing file is missing: $path"
    }
}

$gh = (Get-Command gh -ErrorAction Stop).Source
& $gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

$values = @{}
Get-Content -LiteralPath $secretsPath | ForEach-Object {
    if ($_ -match '^\s*([^=#]+)\s*=\s*(.*)$') {
        $name = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
}

$required = @(
    "REAMICRO_RELEASE_KEY_ALIAS",
    "REAMICRO_RELEASE_STORE_PASSWORD",
    "REAMICRO_RELEASE_KEY_PASSWORD"
)
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace($values[$name])) {
        throw "Signing secrets file is missing $name."
    }
}

function Set-GitHubSecret {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $gh
    foreach ($argument in @("secret", "set", $Name, "--repo", $Repository)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $process.StandardInput.Write($Value)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Failed to configure $Name. $stdout $stderr".Trim()
    }
}

$keystoreBase64 = [regex]::Replace(
    [IO.File]::ReadAllText($base64Path),
    '\s',
    ''
)
if ([string]::IsNullOrWhiteSpace($keystoreBase64)) {
    throw "The base64 keystore file is empty."
}

Set-GitHubSecret -Name "KEYSTORE_B64" -Value $keystoreBase64
Set-GitHubSecret -Name "KEYSTORE_PASSWORD" -Value $values["REAMICRO_RELEASE_STORE_PASSWORD"]
Set-GitHubSecret -Name "KEY_ALIAS" -Value $values["REAMICRO_RELEASE_KEY_ALIAS"]
Set-GitHubSecret -Name "KEY_PASSWORD" -Value $values["REAMICRO_RELEASE_KEY_PASSWORD"]

Write-Host "Configured release signing secrets for $Repository."
