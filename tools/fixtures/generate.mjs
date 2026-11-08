// Builds the same transactions Dustpan builds, but with @solana/web3.js and @solana/spl-token,
// so the Kotlin tests can check our encoder byte for byte.
import { PublicKey, SystemProgram, TransactionMessage } from '@solana/web3.js';
import {
  TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID, NATIVE_MINT,
  createCloseAccountInstruction, createInitializeAccount3Instruction,
} from '@solana/spl-token';

const wallet = new PublicKey('DHSBGF5CTte4ZMtcmsXRYnMiGg5gn5jMo8NKmBA3BTd8');
const blockhash = 'EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k';
const devnetUsdc = new PublicKey('4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU');
const rent = 2039280;
const seedPrefix = 'dustpan-t5abcd-';
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const compile = (instructions) =>
  new TransactionMessage({ payerKey: wallet, recentBlockhash: blockhash, instructions })
    .compileToLegacyMessage().serialize();

// 1) Test-account creation: createAccountWithSeed + InitializeAccount3 for each mint.
const mints = [devnetUsdc, NATIVE_MINT, devnetUsdc];
const seeded = [];
const createIxs = [];
for (const [i, mint] of mints.entries()) {
  const seed = `${seedPrefix}${i}`;
  const account = await PublicKey.createWithSeed(wallet, seed, TOKEN_PROGRAM_ID);
  seeded.push({ seed, address: account.toBase58() });
  createIxs.push(SystemProgram.createAccountWithSeed({
    fromPubkey: wallet, newAccountPubkey: account, basePubkey: wallet, seed,
    lamports: rent, space: 165, programId: TOKEN_PROGRAM_ID,
  }));
  createIxs.push(createInitializeAccount3Instruction(account, mint, wallet, TOKEN_PROGRAM_ID));
}

// 2) A sweep that mixes Token and Token-2022 accounts.
// Deterministic stand-in addresses: they only need to be distinct.
function fakeAddress(i) {
  const bytes = new Uint8Array(32);
  bytes[0] = 7; bytes[15] = (i * 37) & 0xff; bytes[31] = i + 1;
  return new PublicKey(bytes).toBase58();
}
const closeTargets = Array.from({ length: 20 }, (_, i) => ({
  address: fakeAddress(i),
  program: i % 3 === 0 ? TOKEN_2022_PROGRAM_ID : TOKEN_PROGRAM_ID,
}));
const closeIxs = closeTargets.map(({ address, program }) =>
  createCloseAccountInstruction(new PublicKey(address), wallet, wallet, [], program));

console.log(JSON.stringify({
  wallet: wallet.toBase58(),
  blockhash,
  rent,
  seedPrefix,
  devnetUsdc: devnetUsdc.toBase58(),
  seeded,
  createMessageHex: hex(compile(createIxs)),
  closeTargets: closeTargets.map(({ address, program }) => ({ address, program: program.toBase58() })),
  closeMessageHex: hex(compile(closeIxs)),
}, null, 2));
