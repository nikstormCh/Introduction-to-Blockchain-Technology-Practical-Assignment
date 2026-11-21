# Step 3 — PostgreSQL evidence storage

An accepted request has one signed receipt and an ordered list of private messages. The
Spring bean AcceptedRequestEvidenceRepository saves both in one database transaction.
The service can load them by the original acceptedRequestId after restarting.

## Create the local database on Windows

Start your local PostgreSQL server. From the Maven project directory, run the following
once with an owner account that can create a database and role. The script prompts through
psql for authentication and for the new application password. With a different PostgreSQL
installation or port, pass -PostgresBin and -Port.

~~~powershell
.\scripts\Initialize-EvidenceDatabase.ps1
~~~

The script creates proof_of_prompt, installs src/main/resources/db/step3-evidence.sql
and creates the proof_of_prompt_app login. If a step fails, the error identifies the stage.
Inspect the database and role in psql and resume from the first unfinished command in
the script. Protect the cluster data directory and backups with operating-system permissions.
Keep the server local and use password authentication. SQL grants allow the application
to read and insert evidence in the private schema. The administrator controls the schema.

Set the password in the PowerShell session used to launch the application. The following
prompt hides the entered password. The URL and user shown below are the defaults.

~~~powershell
$env:PROOF_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/proof_of_prompt'
$env:PROOF_DATABASE_USER = 'proof_of_prompt_app'
$env:PROOF_DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Application database password' -AsSecureString)).Password
.\mvnw.cmd spring-boot:run
~~~

## Saving and reading from Java

Inject AcceptedRequestEvidenceRepository into the service that handles acceptance.
Pass the same CommittedRequest and SignedRequestAcceptanceReceipt used in step 2.

~~~java
var evidence = new AcceptedRequestEvidence(committedRequest, signedReceipt);
evidenceRepository.saveAcceptedRequestEvidence(evidence);

var restoredEvidence = evidenceRepository.findAcceptedRequestEvidence(expectedRequestId).orElseThrow();
var restoredReceipt = restoredEvidence.signedReceipt();
var disclosure = restoredEvidence.committedRequest().disclose(selectedPosition);
~~~

The caller checks the signature with an independently trusted public key and checks the
expected request ID before using the receipt's root and count for Merkle verification.
Keep that public key accessible across restarts. Reading old evidence requires no private
signing key. Store signing keys separately from message evidence.

The receipt table has a primary key on request ID and a unique receipt ID. The message
table references the same request ID and enforces unique positions within each request.
An attempted duplicate save fails without changing the first receipt. A failed message
insert rolls back its entire request. Missing request IDs yield Optional.empty().
A database error or evidence inconsistency propagates as an error.

The repository writes exact UTC instant text, binary signatures and original random bytes.
UTF-8 byte columns preserve every valid message and identifier, including embedded NUL.
On read, the service checks consecutive positions and rebuilds the original Merkle tree.
The receipt must match the restored ID, count and root. Only the selected disclosure and
the receipt belong in a disclosure package. The full snapshot stays in private storage.
Model outcomes and blockchain transaction references belong to the later integration steps.

## Test on a separate PostgreSQL instance

~~~powershell
.\scripts\Test-PostgresEvidence.ps1
~~~

The script uses PostgreSQL binaries and the Maven wrapper. It creates a disposable cluster,
a test database and a restricted application role. It runs the full test suite, including
a failure during message insertion, then restarts PostgreSQL and launches a second Maven
process. With the saved ID and trusted public key, that process checks the original
receipt bytes, signature, hash and selective disclosure. The private key from the first
process is discarded. The script stops and removes only its own temporary cluster.
For a different installation pass -PostgresBin. A free loopback TCP port is required.
The regular Maven test command runs steps 1 and 2 and the application-context test.
The two PostgreSQL test phases are enabled by the dedicated script.
