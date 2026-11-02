import { after, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { generateKeyPairSync } from 'node:crypto';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, deleteDoc, doc, documentId, getDoc, getDocs, limit, orderBy,
  query, serverTimestamp, setDoc, startAfter, Timestamp, updateDoc, where,
} from 'firebase/firestore';

const projectId = 'demo-trainpit';
const publicKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey
  .export({ type: 'spki', format: 'der' }).toString('base64');
const profiles = {
  trainer: { uid: 'trainer', displayName: 'Trainer One', role: 'TRAINER' },
  client: { uid: 'client', displayName: 'Client One', role: 'CLIENT' },
  trainer2: { uid: 'trainer2', displayName: 'Trainer Two', role: 'TRAINER' },
  client2: { uid: 'client2', displayName: 'Client Two', role: 'CLIENT' },
};
let env;
const db = (uid) => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const profileRef = (store, uid) => doc(store, 'profiles', uid);
const deviceRef = (store, uid, id = 'device') => doc(store, 'profiles', uid, 'devices', id);
const entryRef = (store, uid, id) => doc(store, 'profiles', uid, 'entries', id);
const relationRef = (store, trainer = 'trainer', client = 'client') => doc(store, 'relationships', `${trainer}_${client}`);
const device = (uid, id = 'device') => ({ ownerId: uid, deviceId: id, publicKey, revoked: false });
function relationship(trainer = 'trainer', client = 'client', status = 'PENDING', shareBodyWeight = false) {
  return {
    id: `${trainer}_${client}`, trainerId: trainer, clientId: client,
    trainerName: profiles[trainer].displayName, clientName: profiles[client].displayName,
    members: [trainer, client], status, shareBodyWeight,
  };
}
function entry(ownerId = 'client', kind = 'SESSION', extra = {}) {
  return {
    ownerId, subjectId: kind === 'PLAN' ? 'client' : ownerId, kind,
    rootId: 'root', deviceId: 'device', parents: [], createdAt: 1, deleted: false,
    payload: JSON.stringify({ fixture: 'opaque signed payload; app validates signature and metadata' }),
    serverAt: serverTimestamp(), ...extra,
  };
}
function deltas(store, owner, ...filters) {
  return query(collection(store, 'profiles', owner, 'entries'), ...filters,
    orderBy('serverAt'), orderBy(documentId()), limit(200));
}

before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run these tests inside firebase emulators:exec; production access is forbidden.');
  env = await initializeTestEnvironment({ projectId, firestore: { rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8') } });
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const store = context.firestore();
    for (const p of Object.values(profiles)) {
      await setDoc(profileRef(store, p.uid), p);
      await setDoc(deviceRef(store, p.uid), device(p.uid));
    }
    await setDoc(relationRef(store), relationship('trainer', 'client', 'ACTIVE'));
    await setDoc(relationRef(store, 'trainer2', 'client2'), relationship('trainer2', 'client2', 'ACTIVE'));
    await setDoc(relationRef(store, 'trainer', 'client2'), relationship('trainer', 'client2'));
    await setDoc(entryRef(store, 'client', 'session'), entry());
    await setDoc(entryRef(store, 'client', 'weight'), entry('client', 'WEIGHT'));
    await setDoc(entryRef(store, 'trainer', 'plan'), entry('trainer', 'PLAN'));
    await setDoc(entryRef(store, 'trainer', 'other-plan'), entry('trainer', 'PLAN', { subjectId: 'client2' }));
  });
});

test('unauthenticated requests cannot read or write any application path', async () => {
  const store = db();
  await assertFails(getDoc(profileRef(store, 'client')));
  await assertFails(getDocs(collection(store, 'profiles', 'client', 'devices')));
  await assertFails(getDoc(relationRef(store)));
  await assertFails(getDoc(entryRef(store, 'client', 'session')));
  await assertFails(setDoc(profileRef(store, 'new'), { uid: 'new', displayName: 'Name', role: 'CLIENT' }));
  await assertFails(setDoc(entryRef(store, 'client', 'new'), entry()));
});

test('profiles are fixed-role, exact-UID public lookups without enumeration', async () => {
  const store = db('new');
  await assertSucceeds(setDoc(profileRef(store, 'new'), { uid: 'new', displayName: 'New Client', role: 'CLIENT' }));
  await assertSucceeds(getDoc(profileRef(db('trainer'), 'new')));
  await assertFails(getDocs(collection(store, 'profiles')));
  await assertFails(updateDoc(profileRef(store, 'new'), { role: 'TRAINER' }));
  await assertFails(updateDoc(profileRef(store, 'new'), { displayName: 'Changed' }));
  await assertFails(deleteDoc(profileRef(store, 'new')));
  await assertFails(setDoc(profileRef(store, 'spoofed'), { uid: 'spoofed', displayName: 'Name', role: 'CLIENT' }));
  await assertFails(setDoc(profileRef(db('admin'), 'admin'), { uid: 'admin', displayName: 'Name', role: 'ADMIN' }));
  await assertFails(setDoc(profileRef(db('extra'), 'extra'), { uid: 'extra', displayName: 'Name', role: 'CLIENT', email: 'private@example.invalid' }));
  await assertFails(setDoc(profileRef(db('long'), 'long'), { uid: 'long', displayName: 'x'.repeat(101), role: 'CLIENT' }));
});

test('device keys are public to signed-in users, owner-registered, immutable and permanently revocable', async () => {
  const client = db('client');
  await assertSucceeds(setDoc(deviceRef(client, 'client', 'second'), device('client', 'second')));
  await assertSucceeds(getDoc(deviceRef(db('trainer2'), 'client')));
  await assertSucceeds(getDocs(collection(db('trainer'), 'profiles', 'client', 'devices')));
  await assertFails(setDoc(deviceRef(db('trainer'), 'client', 'foreign'), device('client', 'foreign')));
  await assertFails(setDoc(deviceRef(client, 'client', 'spoofed'), device('trainer', 'spoofed')));
  await assertFails(setDoc(deviceRef(client, 'client', 'bad'), { ...device('client', 'bad'), publicKey: 'bad' }));
  await assertFails(updateDoc(deviceRef(client, 'client'), { publicKey: 'A'.repeat(124) }));
  await assertFails(updateDoc(deviceRef(client, 'client'), { ownerId: 'trainer' }));
  await assertFails(updateDoc(deviceRef(client, 'client'), { deviceId: 'different' }));
  await assertFails(updateDoc(deviceRef(db('trainer'), 'client'), { revoked: true }));
  await assertSucceeds(updateDoc(deviceRef(client, 'client'), { revoked: true }));
  await assertFails(updateDoc(deviceRef(client, 'client'), { revoked: false }));
  await assertFails(deleteDoc(deviceRef(client, 'client')));
  await assertFails(setDoc(entryRef(client, 'client', 'revoked'), entry()));
  await assertSucceeds(getDoc(entryRef(client, 'client', 'session')));
});

test('trainers request registered clients without spoofing roles, names, members or initial consent', async () => {
  const trainer = db('trainer2');
  await assertFails(setDoc(relationRef(db('client'), 'trainer', 'client2'), relationship('trainer', 'client2')));
  for (const patch of [
    { status: 'ACTIVE' }, { shareBodyWeight: true }, { members: ['trainer', 'client', 'trainer2'] },
    { trainerName: 'Forged' }, { clientName: 'Forged' }, { id: 'wrong' }, { extra: true },
  ]) {
    await assertFails(setDoc(relationRef(db('trainer2'), 'trainer2', 'client'), { ...relationship('trainer2', 'client'), ...patch }));
  }
  await assertFails(setDoc(doc(db('client'), 'relationships', 'client_client2'), {
    ...relationship('trainer', 'client2'), id: 'client_client2', trainerId: 'client', members: ['client', 'client2'], trainerName: 'Client One',
  }));
  await assertSucceeds(setDoc(relationRef(trainer, 'trainer2', 'client'), relationship('trainer2', 'client')));
});

test('pending requests expose no sessions or plans; only the client accepts, without implicit weight consent', async () => {
  await assertFails(getDoc(entryRef(db('trainer'), 'client2', 'missing')));
  await assertFails(getDoc(entryRef(db('client2'), 'trainer', 'other-plan')));
  await assertFails(updateDoc(relationRef(db('trainer'), 'trainer', 'client2'), { status: 'ACTIVE' }));
  await assertFails(updateDoc(relationRef(db('client2'), 'trainer', 'client2'), { status: 'ACTIVE', shareBodyWeight: true }));
  await assertSucceeds(updateDoc(relationRef(db('client2'), 'trainer', 'client2'), { status: 'ACTIVE' }));
  await assertSucceeds(getDoc(entryRef(db('client2'), 'trainer', 'other-plan')));
});

test('owned writes enforce role, subject, active plan assignment and immutable revisions', async () => {
  const client = db('client');
  const trainer = db('trainer');
  await assertSucceeds(setDoc(entryRef(client, 'client', 'own-session'), entry()));
  await assertSucceeds(setDoc(entryRef(client, 'client', 'own-weight'), entry('client', 'WEIGHT')));
  await assertSucceeds(setDoc(entryRef(trainer, 'trainer', 'own-plan'), entry('trainer', 'PLAN')));
  await assertFails(setDoc(entryRef(client, 'trainer', 'foreign'), entry('trainer', 'PLAN')));
  await assertFails(setDoc(entryRef(trainer, 'client', 'foreign'), entry()));
  await assertFails(setDoc(entryRef(client, 'client', 'plan'), entry('client', 'PLAN')));
  await assertFails(setDoc(entryRef(trainer, 'trainer', 'session'), entry('trainer', 'SESSION')));
  await assertFails(setDoc(entryRef(client, 'client', 'wrong-subject'), entry('client', 'SESSION', { subjectId: 'client2' })));
  await assertFails(setDoc(entryRef(trainer, 'trainer', 'pending-plan'), entry('trainer', 'PLAN', { subjectId: 'client2' })));
  await assertFails(updateDoc(entryRef(client, 'client', 'session'), { payload: 'overwritten' }));
  for (const patch of [{ ownerId: 'trainer' }, { deviceId: 'second' }, { subjectId: 'client2' }, { parents: ['other'] }]) {
    await assertFails(updateDoc(entryRef(client, 'client', 'session'), patch));
  }
  await assertFails(setDoc(entryRef(client, 'client', 'session'), entry()));
  await assertFails(deleteDoc(entryRef(client, 'client', 'session')));
  await assertSucceeds(setDoc(entryRef(client, 'client', 'tombstone'), entry('client', 'SESSION', { parents: ['session'], deleted: true })));
});

test('active relationships share only targeted plans and sessions, not other clients or trainers', async () => {
  await assertSucceeds(getDoc(entryRef(db('client'), 'trainer', 'plan')));
  await assertSucceeds(getDoc(entryRef(db('trainer'), 'client', 'session')));
  await assertFails(getDoc(entryRef(db('client'), 'trainer', 'other-plan')));
  await assertFails(getDoc(entryRef(db('client2'), 'client', 'session')));
  await assertFails(getDoc(entryRef(db('trainer2'), 'client', 'session')));
  await assertFails(getDoc(entryRef(db('trainer2'), 'trainer', 'plan')));
  await assertFails(getDoc(relationRef(db('client2'))));
});

test('only clients opt in to weight sharing; opt-out and relationship ending revoke subsequent reads', async () => {
  const trainer = db('trainer');
  const client = db('client');
  await assertFails(getDoc(entryRef(trainer, 'client', 'weight')));
  await assertFails(updateDoc(relationRef(trainer), { shareBodyWeight: true }));
  await assertSucceeds(updateDoc(relationRef(client), { shareBodyWeight: true }));
  await assertSucceeds(getDoc(entryRef(trainer, 'client', 'weight')));
  await assertSucceeds(updateDoc(relationRef(client), { shareBodyWeight: false }));
  await assertFails(getDoc(entryRef(trainer, 'client', 'weight')));
  await assertSucceeds(getDoc(entryRef(trainer, 'client', 'session')));
  await assertSucceeds(updateDoc(relationRef(client), { shareBodyWeight: true }));
  await assertFails(updateDoc(relationRef(trainer), { status: 'ENDED' }));
  await assertSucceeds(updateDoc(relationRef(trainer), { status: 'ENDED', shareBodyWeight: false }));
  await assertFails(getDoc(entryRef(trainer, 'client', 'session')));
  await assertFails(getDoc(entryRef(trainer, 'client', 'weight')));
  await assertFails(getDoc(entryRef(client, 'trainer', 'plan')));
  await assertSucceeds(getDoc(entryRef(client, 'client', 'weight')));
  await assertSucceeds(getDoc(entryRef(trainer, 'trainer', 'plan')));
});

test('ended relationships require trainer re-request and fresh client acceptance, never retained weight consent', async () => {
  const trainer = db('trainer');
  const client = db('client');
  await assertSucceeds(updateDoc(relationRef(client), { status: 'ENDED', shareBodyWeight: false }));
  await assertFails(updateDoc(relationRef(client), { status: 'PENDING' }));
  await assertFails(updateDoc(relationRef(trainer), { status: 'ACTIVE' }));
  await assertFails(updateDoc(relationRef(trainer), { status: 'PENDING', shareBodyWeight: true }));
  await assertSucceeds(updateDoc(relationRef(trainer), { status: 'PENDING', shareBodyWeight: false }));
  await assertFails(getDoc(entryRef(trainer, 'client', 'session')));
  await assertSucceeds(updateDoc(relationRef(client), { status: 'ACTIVE' }));
  await assertSucceeds(getDoc(entryRef(trainer, 'client', 'session')));
  await assertFails(getDoc(entryRef(trainer, 'client', 'weight')));
});

test('relationship parties and identity fields cannot be changed or deleted; clients may reject', async () => {
  for (const patch of [
    { trainerId: 'trainer2' }, { clientId: 'client2' }, { members: ['trainer', 'client2'] },
    { trainerName: 'Changed' }, { clientName: 'Changed' }, { id: 'changed' }, { extra: true },
  ]) await assertFails(updateDoc(relationRef(db('client')), patch));
  await assertFails(deleteDoc(relationRef(db('trainer'))));
  await assertFails(updateDoc(relationRef(db('client2')), { status: 'ENDED', shareBodyWeight: false }));
  await assertSucceeds(updateDoc(relationRef(db('client2'), 'trainer', 'client2'), { status: 'ENDED', shareBodyWeight: false }));
});

test('delta queries require kind/subject authorization and bounded pages; owner pages and cursors work', async () => {
  const trainer = db('trainer');
  const client = db('client');
  await assertSucceeds(getDocs(deltas(client, 'client')));
  const first = await assertSucceeds(getDocs(query(collection(client, 'profiles', 'client', 'entries'), orderBy('serverAt'), orderBy(documentId()), limit(1))));
  await assertSucceeds(getDocs(query(collection(client, 'profiles', 'client', 'entries'), orderBy('serverAt'), orderBy(documentId()), startAfter(first.docs[0]), limit(200))));
  await assertFails(getDocs(collection(client, 'profiles', 'client', 'entries')));
  await assertFails(getDocs(query(collection(client, 'profiles', 'client', 'entries'), limit(201))));
  await assertSucceeds(getDocs(deltas(trainer, 'client', where('kind', '==', 'SESSION'))));
  await assertFails(getDocs(deltas(trainer, 'client')));
  await assertFails(getDocs(deltas(trainer, 'client', where('kind', 'in', ['SESSION', 'WEIGHT']))));
  await assertSucceeds(updateDoc(relationRef(client), { shareBodyWeight: true }));
  await assertSucceeds(getDocs(deltas(trainer, 'client', where('kind', 'in', ['SESSION', 'WEIGHT']))));
  await assertSucceeds(getDocs(deltas(client, 'trainer', where('kind', '==', 'PLAN'), where('subjectId', '==', 'client'))));
  await assertFails(getDocs(deltas(client, 'trainer', where('kind', '==', 'PLAN'))));
  await assertFails(getDocs(deltas(db('trainer2'), 'client', where('kind', '==', 'SESSION'))));
  await assertFails(getDocs(collection(trainer, 'relationships')));
  await assertSucceeds(getDocs(query(collection(trainer, 'relationships'), where('members', 'array-contains', 'trainer'))));
  await assertFails(getDocs(query(collection(db('trainer2'), 'relationships'), where('members', 'array-contains', 'trainer'))));
});

test('entry metadata, type and size bounds reject malformed or excessive submissions', async () => {
  const store = db('client');
  const invalid = [
    { ownerId: 'client2' }, { subjectId: 'client2' }, { deviceId: 'unregistered' }, { rootId: '' },
    { rootId: 'x'.repeat(129) }, { createdAt: -1 }, { createdAt: 1.5 }, { createdAt: 253402300800000 },
    { deleted: 'false' }, { kind: 'ADMIN' }, { parents: 'wrong' }, { parents: ['same', 'same'] },
    { parents: Array.from({ length: 101 }, (_, i) => `parent${i}`) }, { parents: ['bad'] },
    { parents: [1] }, { parents: [null] }, { parents: [{}] }, { parents: [''] },
    { parents: ['x'.repeat(129)] }, { parents: ['not/an/id'] },
    { payload: '' }, { payload: 'x'.repeat(262145) }, { payload: {} },
    { serverAt: Timestamp.fromMillis(1) }, { unexpected: true },
  ];
  for (const patch of invalid) await assertFails(setDoc(entryRef(store, 'client', 'bad'), entry('client', 'SESSION', patch)));
  const missing = entry();
  delete missing.payload;
  await assertFails(setDoc(entryRef(store, 'client', 'bad'), missing));
  await assertFails(setDoc(doc(store, 'unspecified', 'data'), { value: true }));
  await assertFails(getDoc(doc(store, 'unspecified', 'data')));
});
