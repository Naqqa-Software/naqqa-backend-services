$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envFile)) {
    Write-Error "Missing .env file. Copy .env.example to .env and fill values."
    exit 1
}

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        $name = $parts[0].Trim()
        $value = $parts[1]
        [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

Set-Location (Join-Path $PSScriptRoot "..")
mvn spring-boot:run

