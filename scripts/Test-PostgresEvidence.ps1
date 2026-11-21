param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateRange(1024, 65535)][int]$Port = (Get-Random -Minimum 49152 -Maximum 65000)
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$postgresControl = Join-Path $PostgresBin 'pg_ctl.exe'
$postgresClient = Join-Path $PostgresBin 'psql.exe'
$initializeCluster = Join-Path $PostgresBin 'initdb.exe'
foreach ($tool in @($postgresControl, $postgresClient, $initializeCluster)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing PostgreSQL tool: $tool" }
}

$temporaryParent = (Get-Item -LiteralPath ([IO.Path]::GetTempPath())).FullName
$runName = 'proof-evidence-' + [Guid]::NewGuid().ToString('N')
$runDirectory = (New-Item -Type Directory -Path (Join-Path $temporaryParent $runName)).FullName
$clusterDirectory = Join-Path $runDirectory 'cluster'
$passwordFile = Join-Path $runDirectory 'init-password'
$serverLog = Join-Path $runDirectory 'postgres.log'
$previousSettings = @{}
foreach ($name in @('PGPASSWORD','PROOF_TEST_APP_PASSWORD','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
                    'SPRING_DATASOURCE_PASSWORD','PROOF_RESTART_CHECK_DIR','PROOF_RESTART_PHASE')) {
    $previousSettings[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Confirm-CommandSucceeded([string]$description) {
    if ($LASTEXITCODE -ne 0) { throw "$description failed with exit code $LASTEXITCODE" }
}

Push-Location $repositoryRoot
try {
    $env:PGPASSWORD = [Guid]::NewGuid().ToString('N')
    $env:PROOF_TEST_APP_PASSWORD = [Guid]::NewGuid().ToString('N')
    Set-Content -LiteralPath $passwordFile -Value $env:PGPASSWORD -Encoding Ascii
    & $initializeCluster -D $clusterDirectory -U test_owner -A scram-sha-256 "--pwfile=$passwordFile" --encoding=UTF8 --locale=C
    Confirm-CommandSucceeded 'Cluster initialization'
    Remove-Item -LiteralPath $passwordFile
    & $postgresControl -D $clusterDirectory -l $serverLog -o "-h 127.0.0.1 -p $Port" -w start
    Confirm-CommandSucceeded 'Starting the private test server'

    $clientArgs = @('-X','-w','-h','127.0.0.1','-p',$Port,'-U','test_owner','-v','ON_ERROR_STOP=1')
    & $postgresClient @clientArgs -d postgres -c 'CREATE DATABASE proof_of_prompt_test'
    Confirm-CommandSucceeded 'Creating the test database'
    & $postgresClient @clientArgs -d proof_of_prompt_test -f 'src/main/resources/db/step3-evidence.sql'
    Confirm-CommandSucceeded 'Installing the schema'
    @'
\getenv app_password PROOF_TEST_APP_PASSWORD
CREATE ROLE evidence_test_app LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE proof_of_prompt_test TO evidence_test_app;
GRANT USAGE ON SCHEMA proof_of_prompt TO evidence_test_app;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA proof_of_prompt TO evidence_test_app;
ALTER TABLE proof_of_prompt.request_messages ADD CONSTRAINT simulate_message_insert_failure
    CHECK (message_content_utf8 <> convert_to('Trigger the test-only constraint', 'UTF8'));
'@ | & $postgresClient @clientArgs -d proof_of_prompt_test -f -
    Confirm-CommandSucceeded 'Preparing the test role and rollback fixture'
    & $postgresClient @clientArgs -d proof_of_prompt_test -v 'app_role=evidence_test_app' -f 'src/main/resources/db/step4-model-executions.sql'
    Confirm-CommandSucceeded 'Installing model execution storage'

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$Port/proof_of_prompt_test"
    $env:SPRING_DATASOURCE_USERNAME = 'evidence_test_app'
    $env:SPRING_DATASOURCE_PASSWORD = $env:PROOF_TEST_APP_PASSWORD
    $env:PROOF_RESTART_CHECK_DIR = $runDirectory
    $env:PROOF_RESTART_PHASE = 'write'
    & .\mvnw.cmd test
    Confirm-CommandSucceeded 'Writing evidence and running regression tests'
    if (-not (Test-Path (Join-Path $runDirectory 'receipt-hash'))) { throw 'The write test did not complete' }

    & $postgresControl -D $clusterDirectory -l $serverLog -m fast -w restart
    Confirm-CommandSucceeded 'Restarting the same PostgreSQL cluster'
    $env:PROOF_RESTART_PHASE = 'read'
    & .\mvnw.cmd '-Dtest=PostgresEvidenceRestartTest#restoreTheOriginalSignedReceiptAndDiscloseOneMessage' test
    Confirm-CommandSucceeded 'Verifying evidence in a fresh application process'
    if (-not (Test-Path (Join-Path $runDirectory 'verified-after-restart'))) { throw 'The read test did not complete' }
} finally {
    Pop-Location
    foreach ($name in $previousSettings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousSettings[$name])
    }
    if (Test-Path -LiteralPath (Join-Path $clusterDirectory 'postmaster.pid')) {
        & $postgresControl -D $clusterDirectory -m fast -w stop
        Confirm-CommandSucceeded 'Stopping the private test server'
    }
    $resolvedRunDirectory = (Get-Item -LiteralPath $runDirectory).FullName
    $expectedRunDirectory = [IO.Path]::GetFullPath((Join-Path $temporaryParent $runName))
    if ($resolvedRunDirectory -ne $expectedRunDirectory) { throw 'Unexpected cleanup path' }
    Remove-Item -LiteralPath $resolvedRunDirectory -Recurse -Force
}
