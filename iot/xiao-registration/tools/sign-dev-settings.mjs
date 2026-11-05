import { createHash, createPrivateKey, createPublicKey, sign } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const proofVersion = 'tingle-dev-settings-proof-v1';
const payloadFormat = 'tingle-dev-settings-v1';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const identifier = /^[A-Za-z0-9._-]{1,64}$/;

function base64url(value, size) {
  if (typeof value !== 'string') throw new Error('Expected Base64url string');
  const bytes = Buffer.from(value, 'base64url');
  if (bytes.length !== size || bytes.toString('base64url') !== value) {
    throw new Error('Invalid canonical Base64url');
  }
  return bytes;
}

function int64(value) {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value) ||
      BigInt(value) > 9223372036854775807n) {
    throw new Error('Expected a positive int64 as a decimal string');
  }
  return value;
}

export function signDevSettings(privateKey, fields) {
  const { serial, keyId, ownerGeneration, syncRunId, nonce, deviceNonce, serverTime } = fields;
  if (privateKey.asymmetricKeyType !== 'ed25519' ||
      ![serial, keyId].every(value => typeof value === 'string' && identifier.test(value)) ||
      ![ownerGeneration, syncRunId].every(value => typeof value === 'string' && uuid.test(value))) {
    throw new Error('Invalid signing key or device/run identity');
  }
  const deviceId = int64(fields.deviceId);
  const version = int64(fields.settingsVersion);
  base64url(nonce, 32);
  base64url(deviceNonce, 32);
  const timestamp = new Date(serverTime);
  if (typeof serverTime !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(serverTime) ||
      !Number.isFinite(timestamp.getTime()) || timestamp.getTime() < 1000 ||
      timestamp.toISOString().slice(0, 19) !== serverTime.slice(0, 19)) {
    throw new Error('Expected ISO 8601 UTC serverTime');
  }
  const payload = base64url(fields.payload, 1);
  const hash = createHash('sha256').update(payload).digest('hex');
  if (payload[0] > 1 || fields.payloadFormat !== payloadFormat || fields.payloadHash !== hash) {
    throw new Error('Invalid settings payload');
  }
  const publicKey = createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).subarray(-32);
  const serverKeyId = 'dev-ed25519-' + publicKey.subarray(0, 8).toString('hex');
  const transcript = Buffer.from([
    'TINGLE-KEYRING-SERVER-SETTINGS', proofVersion, serverKeyId, serial, keyId, deviceId,
    ownerGeneration, syncRunId, version, payloadFormat, hash, nonce, deviceNonce,
    String(Math.floor(timestamp.getTime() / 1000)), '',
  ].join('\n'), 'utf8');
  return {
    serverProofProtocolVersion: proofVersion,
    serverKeyId,
    deviceNonce,
    serverTime,
    serverSignature: sign(null, transcript, privateKey).toString('base64url'),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 4) {
    throw new Error('Usage: node sign-dev-settings.mjs <Ed25519-private-pem> <settings-signing-input.json>');
  }
  const key = createPrivateKey(readFileSync(process.argv[2]));
  const input = JSON.parse(readFileSync(process.argv[3], 'utf8'));
  console.log(JSON.stringify(signDevSettings(key, input), null, 2));
}
