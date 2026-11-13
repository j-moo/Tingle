// Independent Node/OpenSSL verification of C-produced public test evidence.
// No server or device private key is read by this verifier.
import {readFileSync} from 'node:fs';
import {createHash, createPublicKey, verify} from 'node:crypto';
import assert from 'node:assert/strict';
const input = readFileSync(process.argv[2], 'utf8').trim();
const values = input.startsWith('{')
  ? Object.fromEntries(Object.entries(JSON.parse(input).vectors).map(([name, entry]) => [name, Buffer.from(entry.base64url, 'base64url')]))
  : Object.fromEntries(input.split(/\r?\n/)
    .map(line => { const at = line.indexOf('='); return [line.slice(0, at), Buffer.from(line.slice(at + 1), 'hex')]; }));
if (values.statusResponse) {
  // IoT 자체 복사본과 비교한다. 앱 복사본과의 일치는 같은 SHA-256 고정으로 보장한다 (app BleOwnerFlowFixtureTest).
  const fixture = readFileSync(new URL('../tests/data/status-0.7.json', import.meta.url));
  assert.equal(createHash('sha256').update(fixture).digest('hex'), 'e8809676ee5f40cd75c0769143ccbef19659c20d5e87b436a18f0f4811894bcd');
  assert.deepEqual(fixture, values.statusResponse);
  assert(values.statusResponse.length > 512 && values.statusResponse.length <= 2304);
}
const hash = bytes => createHash('sha256').update(bytes).digest();
const key = raw => createPublicKey({key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw]), type:'spki', format:'der'});
function check(publicKey, message, signature) { assert(verify(null, message, key(publicKey), signature)); }
const server = values.serverPublic;
function grant(g) {
  assert([3,4].includes(g[0])); assert.equal(g.length, 261);
  assert(g.readBigUInt64LE(117) > g.readBigUInt64LE(125));
  check(server, Buffer.concat([Buffer.from(g[0] === 4 ? 'TINGLE-PEER-GRANT-V3\n' : 'TINGLE-PEER-GRANT-V2\n'), g.subarray(0, 197)]), g.subarray(197));
}
function certificate(c, ephemeral) {
  grant(c.subarray(0,261));
  check(c.subarray(165,197), Buffer.concat([Buffer.from('TINGLE-PEER-AUTH-V1\n'), ephemeral, c.subarray(0,261)]), c.subarray(261));
}
grant(values.grant);
assert(['TSV2','TSV3'].includes(values.bundle.toString('ascii',0,4)));
const v3 = values.bundle[3] === 51;
if (v3) { assert.equal(values.bundle.readUInt32LE(483), values.grant.readUInt32LE(25)); }
assert.deepEqual(values.bundle.subarray(200,461), values.grant);
assert.deepEqual(hash(values.bundle.subarray(4,200)), values.grant.subarray(133,165));
for (const name of ['settings','owner','receipt','release']) {
  check(server, values[`${name}Transcript`], values[`${name}Signature`]);
  assert.equal(values[`${name}Transcript`].at(-1), 10);
}
if (values.accountLockTranscript) {
  check(server, values.accountLockTranscript, values.accountLockSignature);
  assert.equal(values.accountLockTranscript.at(-1), 10);
  const lines = values.accountLockTranscript.toString('utf8').split('\n');
  if (lines[1] === 'tingle-account-lock-v2') {
    assert.equal(values.peerCredential.length, 357);
    certificate(values.peerCredential.subarray(0,325), values.peerCredential.subarray(325));
    assert.equal(lines.at(-2), hash(values.peerCredential).toString('hex'));
  }
}
const text = values.settingsTranscript.toString('utf8').split('\n');
assert.equal(text[10], hash(values.bundle).toString('hex'));
assert.equal(text[9], v3 ? 'tingle-keyring-settings-v3' : 'tingle-keyring-settings-v2');
if (values.appliedResponse) {
  const [serial, keyId, deviceId, owner, run, version, , payloadHash, runNonce] = text.slice(3);
  const epoch = text[14];
  const response = JSON.parse(values.appliedResponse.toString('utf8'));
  const proofVersion = 'tingle-settings-applied-v1';
  const signature = Buffer.from(response.proof.signature, 'base64url');
  assert.equal(signature.length, 64);
  assert.deepEqual(response, {
    protocolVersion: 1, type: 'SETTINGS_APPLIED_PROOF', requestId: 'test', syncRunId: run,
    settingsVersion: Number(version), payloadHash,
    proof: {protocolVersion: proofVersion, keyId, signature: signature.toString('base64url')},
  });
  const message = Buffer.from([
    'TINGLE-KEYRING-APPLIED', proofVersion, keyId, serial, deviceId, owner, epoch,
    run, runNonce, version, payloadHash, 'APPLIED', '',
  ].join('\n'), 'utf8');
  const devicePublic = values.grant.subarray(165, 197);
  check(devicePublic, message, signature);
  assert(!verify(null, message.subarray(0, -1), key(devicePublic), signature));
  const wrongNonce = Buffer.from(message.toString('utf8').replace(`\n${runNonce}\n`, `\n${'B'.repeat(43)}\n`));
  assert(!verify(null, wrongNonce, key(devicePublic), signature));
  console.log('OpenSSL interop: applied proof verified over the server-run fields and final LF');
}
const envelope = values.encounter;
assert.equal(envelope.toString('ascii',0,4), 'TER2');
const wireBytes = envelope.readUInt16LE(4), evidenceBytes = envelope.readUInt16LE(6);
assert.equal(evidenceBytes,795); assert.equal(envelope.length,24+wireBytes+evidenceBytes+64);
const wire = envelope.subarray(24,24+wireBytes), evidence = envelope.subarray(24+wireBytes,-64);
assert.equal(evidence.toString('ascii',0,4),'KEV2');
assert.equal(evidence.readUInt32LE(4),wire.readUInt32LE(4));
assert.equal(evidence.readBigUInt64LE(8),wire.readBigUInt64LE(24));
const ownLen = wire.readUInt16LE(48), peerLen = wire.readUInt16LE(50);
assert.equal(wire.length,52+ownLen+peerLen); assert.equal(ownLen,230);
assert.deepEqual(hash(wire.subarray(52+34,52+ownLen)),evidence.subarray(16,48));
assert.deepEqual(hash(wire.subarray(52+ownLen)),evidence.subarray(48,80));
const own = evidence.subarray(80,405), peer = evidence.subarray(437,762);
certificate(own,evidence.subarray(405,437)); certificate(peer,evidence.subarray(762,794));
assert.equal(evidence[794],1);
assert.deepEqual(evidence.subarray(16,48),own.subarray(133,165));
assert.deepEqual(hash(wire.subarray(52+ownLen+14+34,-4)),peer.subarray(133,165));
const submission = hash(envelope.subarray(24,-64)).subarray(0,16);
submission[6] = submission[6] & 15 | 0x50; submission[8] = submission[8] & 63 | 0x80;
assert.deepEqual(submission,envelope.subarray(8,24));
check(own.subarray(165,197),Buffer.concat([Buffer.from('TINGLE-ENCOUNTER-V2\n'),hash(envelope.subarray(0,-64))]),envelope.subarray(-64));
assert(values.receiptTranscript.toString('utf8').includes(`\n${hash(envelope).toString('hex')}\n`));
// Verifier must reject a bit change in an otherwise well-formed encounter.
const tampered = Buffer.from(envelope); tampered[40] ^= 1;
assert(!verify(null,Buffer.concat([Buffer.from('TINGLE-ENCOUNTER-V2\n'),hash(tampered.subarray(0,-64))]),key(own.subarray(165,197)),tampered.subarray(-64)));
console.log('OpenSSL interop: peer grant, settings, owner session, both device credentials, immutable encounter and durable receipt passed');
