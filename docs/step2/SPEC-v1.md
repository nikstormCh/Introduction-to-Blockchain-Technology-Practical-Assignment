# Step 2: signed request acceptance receipts

The service signs the final ordered request snapshot and the selected model ID at acceptance.
A successful signature check confirms what the service attested. Model delivery, execution,
understanding and compliance require separate evidence. The timestamp is the service's clock reading.

## Receipt v1

| JSON member | Value |
| --- | --- |
| acceptedRequestId | Lowercase hyphenated UUID generated once for the committed request |
| messageCountInRequest | Positive JSON integer from the committed request (maximum 2147483647) |
| receiptId | Fresh lowercase hyphenated UUID for this receipt |
| receiptProtocolVersion | JSON integer 1 |
| receiptSignatureBase64 | In the full receipt only: padded standard Base64 of the 64 Ed25519 signature bytes |
| receiptSigningKeyId | Non-blank service key identifier |
| requestAcceptanceStatus | String ACCEPTED |
| requestAcceptedAtUtc | UTC instant ending in Z, using Java Instant.toString() with 0, 3, 6 or 9 fractional digits |
| requestMessagesMerkleRoot | 64 lowercase hexadecimal digits from the same committed request |
| selectedModelId | Non-blank configured model identifier |

The signed body is one flat JSON object containing the nine members other than the signature.
The full receipt is the same flat object with the signature inserted at its sorted position.
The Java record groups the body and signature for convenience. Use CanonicalReceiptEncoder
for protocol bytes, including for future export; default record serialization has a different shape.

Use the member order in the table, UTF-8 without BOM, and no insignificant whitespace or final
newline. Integers use decimal digits. Strings use the same escaping as CanonicalMessage from
step 1. Preserve Unicode, reject unpaired surrogates and perform no trimming or normalization.
Use the named enum value, canonical UUID text and UTC time string before encoding.

1. Sign the canonical body bytes directly with Ed25519 using the service's private key.
2. Encode the signature in padded standard Base64 and add it to form the complete receipt.
3. Compute SHA-256 of one byte 0x02 followed by the complete canonical receipt bytes. Represent
   the digest as 64 lowercase hexadecimal digits. Step 5 will submit this digest to the registry.

## Verification and boundaries

Start with a trusted public key and expected request ID obtained independently of the submitted
evidence. The key ID is signed metadata. The verifier receives the key as an explicit argument.
Verify the signature first, then check recordsAcceptanceOf(expectedRequestId). Only then use
the signed root and count to validate a selected message with MerkleTree.verify.
A genuine receipt for another request can pass the signature check and fail the request check.

Receipt objects contain no message text or message secrets. Invalid field values or malformed
Base64 are rejected during construction. A well-formed receipt with an incorrect signature
returns false. Runtime or key configuration failures are errors. A successful local check is
one input to the full verification result in step 6.

## Run from the repository root (PowerShell, Java 21)

```powershell
.\mvnw.cmd test
java -cp target/classes ee.ut.proofofprompt.proof_of_prompt.demos.ReceiptDemo
```

The demo generates an ephemeral Ed25519 key pair, commits three messages, issues a receipt,
and verifies the developer message. The ID, signature and hash vary between runs.
Applications supply their own private signing key to RequestAcceptanceReceiptIssuer and obtain
the trusted public key independently. The demo key is only for the current process.

RequestAcceptanceReceiptTest checks individual field substitutions, signature damage, wrong keys,
request identity, and message inclusion. Its two UTF-8 JSON fixtures and expected public key,
signature and receipt hash were generated independently with Node.js crypto and sorted JSON keys.
The test reads those fixed values and never updates them.
