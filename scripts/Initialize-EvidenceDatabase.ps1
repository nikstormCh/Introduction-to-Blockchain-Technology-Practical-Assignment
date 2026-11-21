param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateRange(1, 65535)][int]$Port = 5432,
    [string]$DatabaseOwner = 'postgres'
)

$ErrorActionPreference = 'Stop'
$client = Join-Path $PostgresBin 'psql.exe'
$clientArgs = @('-X', '-h', '127.0.0.1', '-p', $Port, '-U', $DatabaseOwner, '-v', 'ON_ERROR_STOP=1')
$schemaFile = Join-Path $PSScriptRoot '../src/main/resources/db/step3-evidence.sql'

# Run once on a local server. Existing databases and roles are never replaced.
& $client @clientArgs -d postgres -c 'CREATE DATABASE proof_of_prompt'
if ($LASTEXITCODE -ne 0) { throw 'Database creation failed. Check the server, port and database name.' }
& $client @clientArgs -d proof_of_prompt -f $schemaFile
if ($LASTEXITCODE -ne 0) { throw 'Could not install the evidence schema.' }

# psql prompts for the application password without embedding it in a command or source file.
& $client @clientArgs -d proof_of_prompt -c 'CREATE ROLE proof_of_prompt_app LOGIN' -c '\password proof_of_prompt_app' -c 'REVOKE ALL ON DATABASE proof_of_prompt FROM PUBLIC' -c 'GRANT CONNECT ON DATABASE proof_of_prompt TO proof_of_prompt_app' -c 'GRANT USAGE ON SCHEMA proof_of_prompt TO proof_of_prompt_app' -c 'GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA proof_of_prompt TO proof_of_prompt_app'
if ($LASTEXITCODE -ne 0) { throw 'Application role setup failed. Inspect the last successful command before resuming.' }
& $client @clientArgs -d proof_of_prompt -v 'app_role=proof_of_prompt_app' -f (Join-Path $PSScriptRoot '../src/main/resources/db/step4-model-executions.sql')
if ($LASTEXITCODE -ne 0) { throw 'Model execution schema setup failed.' }
