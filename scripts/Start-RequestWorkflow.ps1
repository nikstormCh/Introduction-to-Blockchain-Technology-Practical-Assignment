param([switch]$CreateSigningKey)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousSettings = @{}
foreach ($setting in @('PROOF_SIGNING_KEYSTORE', 'PROOF_SIGNING_PASSWORD', 'PROOF_DATABASE_PASSWORD', 'GROQ_API_KEY')) {
    $previousSettings[$setting] = [Environment]::GetEnvironmentVariable($setting)
}
function Read-LocalSecret([string]$label) {
    $secret = Read-Host $label -AsSecureString
    return [System.Net.NetworkCredential]::new('', $secret).Password
}

Push-Location $repositoryRoot
try {
    if (-not $env:PROOF_SIGNING_KEYSTORE) {
        $env:PROOF_SIGNING_KEYSTORE = Join-Path $env:LOCALAPPDATA 'ProofOfPrompt\receipt-signer.p12'
    }
    if (-not $env:PROOF_SIGNING_PASSWORD) {
        $env:PROOF_SIGNING_PASSWORD = Read-LocalSecret 'Receipt signing keystore password'
    }
    if ($CreateSigningKey) {
        if (Test-Path -LiteralPath $env:PROOF_SIGNING_KEYSTORE) { throw 'Signing keystore already exists. Start without -CreateSigningKey.' }
        $keyDirectory = Split-Path -Parent $env:PROOF_SIGNING_KEYSTORE
        [void][IO.Directory]::CreateDirectory($keyDirectory)
        $temporaryKey = Join-Path $keyDirectory ([Guid]::NewGuid().ToString('N') + '.p12')
        try {
            & keytool -genkeypair -alias receipt-signer -keyalg Ed25519 -keystore $temporaryKey -storetype PKCS12 -storepass:env PROOF_SIGNING_PASSWORD -dname 'CN=Proof of Prompt' -validity 3650
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the Ed25519 signing key.' }
            [IO.File]::Move($temporaryKey, $env:PROOF_SIGNING_KEYSTORE)
        } finally {
            if (Test-Path -LiteralPath $temporaryKey) { Remove-Item -LiteralPath $temporaryKey }
        }
    }
    if (-not (Test-Path -LiteralPath $env:PROOF_SIGNING_KEYSTORE -PathType Leaf)) {
        throw 'Signing keystore missing. Use -CreateSigningKey for the first run.'
    }
    if (-not $env:PROOF_DATABASE_PASSWORD) { $env:PROOF_DATABASE_PASSWORD = Read-LocalSecret 'Password for the application database role' }
    if (-not $env:GROQ_API_KEY) { $env:GROQ_API_KEY = Read-LocalSecret 'Groq API key' }
    & .\mvnw.cmd '-Dspring-boot.run.profiles=model-request' spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Application startup or execution failed.' }
} finally {
    Pop-Location
    foreach ($setting in $previousSettings.Keys) {
        [Environment]::SetEnvironmentVariable($setting, $previousSettings[$setting])
    }
}
