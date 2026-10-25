'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const keepLatest = require('./keep-latest-artifacts.cjs');

const NAME = 'android-test-reports';
function value(id, day, options = {}) {
  return { id, name: NAME, created_at: `2026-10-${String(day).padStart(2, '0')}T12:00:00Z`,
    expired: false, workflow_run: { id: 700 }, ...options };
}
function fixture(values, { uploaded = 30, event = 'push', actor = 'maintainer', payload = {}, get, remove } = {}) {
  const state = new Map(values.map(item => [item.id, { ...item }]));
  const deleted = [], gets = [], listings = [], warnings = [];
  const missing = () => Object.assign(new Error('not found'), { status: 404 });
  const actions = {
    listArtifactsForRepo: Symbol('repository artifacts endpoint'),
    async getArtifact(args) {
      gets.push(args.artifact_id);
      if (get) return get(args, state, missing);
      if (!state.has(args.artifact_id)) throw missing();
      return { data: { ...state.get(args.artifact_id) } };
    },
    async deleteArtifact(args) {
      deleted.push(args.artifact_id);
      if (remove) return remove(args, state, missing);
      if (!state.delete(args.artifact_id)) throw missing();
      return { status: 204 };
    },
  };
  const github = { rest: { actions }, async paginate(endpoint, args) {
    assert.equal(endpoint, actions.listArtifactsForRepo); listings.push(args);
    return values.map(item => ({ ...item }));
  } };
  const context = { repo: { owner: 'owner', repo: 'repo' }, runId: 700, eventName: event, actor, payload };
  const core = { info() {}, warning(message) { warnings.push(message); } };
  return { state, deleted, gets, listings, warnings, github, context, core,
    invoke(extra = {}) { return keepLatest({ github, context, core, artifactName: NAME, uploadedArtifactId: String(uploaded), ...extra }); } };
}

test('repository pagination and exact-name scope keep newest timestamp then ID', async () => {
  const f = fixture([value(30, 25), value(10, 23), value(20, 25), value(99, 26, { name: 'android-test-reports-extra' }),
    value(100, 26, { name: 'herdr-pocket-debug' }), value(10, 23)]);
  const report = await f.invoke();
  assert.equal(report.kept, 30); assert.deepEqual(f.deleted, [10, 20]);
  assert.deepEqual(f.listings, [{ owner: 'owner', repo: 'repo', name: NAME, per_page: 100 }]);
  assert.ok(f.state.has(30) && f.state.has(99) && f.state.has(100));
});

test('failed or absent upload never removes previous artifacts', async () => {
  const f = fixture([value(30, 25), value(10, 23)]);
  assert.match((await f.invoke({ uploadedArtifactId: '' })).skipped, /no successful upload/);
  assert.deepEqual(f.gets, []); assert.deepEqual(f.listings, []); assert.deepEqual(f.deleted, []);
});

test('expired upload skips cleanup and expired historical entries are not deleted', async () => {
  const expired = fixture([value(30, 25, { expired: true }), value(10, 23)]);
  assert.match((await expired.invoke()).skipped, /expired/); assert.deepEqual(expired.deleted, []);
  const f = fixture([value(30, 25), value(10, 23, { expired: true }), value(20, 24)]);
  const report = await f.invoke(); assert.deepEqual(report.expiredSkipped, [10]);
  assert.deepEqual(f.deleted, [20]); assert.ok(f.state.has(30));
});

test('concurrent newer upload wins even if this job uploaded the older artifact', async () => {
  const f = fixture([value(10, 23), value(30, 25), value(40, 26, { workflow_run: { id: 701 } })]);
  const report = await f.invoke(); assert.equal(report.kept, 40);
  assert.deepEqual(f.deleted, [10, 30]); assert.deepEqual([...f.state.keys()], [40]);
});

test('404 deletion is benign; disappearing replacement stops further deletions', async () => {
  const f = fixture([value(10, 23), value(20, 24), value(30, 25)], { remove(args, state, missing) {
    state.delete(args.artifact_id); if (args.artifact_id === 10) throw missing(); return { status: 204 };
  } });
  const report = await f.invoke(); assert.deepEqual(report.alreadyGone, [10]); assert.deepEqual(report.deleted, [20]);
  const race = fixture([value(10, 23), value(30, 25)], { get(args, state, missing) {
    if (race.gets.length > 1) throw missing(); return { data: state.get(args.artifact_id) };
  } });
  assert.match((await race.invoke()).skipped, /replacement disappeared/); assert.deepEqual(race.deleted, []);
});

test('non-404 delete failure is visible, stops cleanup and preserves the keeper', async () => {
  const f = fixture([value(10, 23), value(20, 24), value(30, 25)], { remove() {
    throw Object.assign(new Error('permission denied'), { status: 403 });
  } });
  await assert.rejects(f.invoke(), /permission denied/);
  assert.deepEqual(f.deleted, [10]); assert.ok(f.state.has(30)); assert.equal(f.warnings.length, 1);
});

test('fork/Dependabot events skip and malformed or out-of-scope identities fail closed', async () => {
  for (const options of [
    { event: 'pull_request', payload: { pull_request: { head: { repo: { fork: true, full_name: 'fork/repo' } } } } },
    { actor: 'dependabot[bot]' }, { event: 'pull_request_target' },
  ]) {
    const f = fixture([value(30, 25)], options);
    assert.match((await f.invoke()).skipped, /PR event/); assert.deepEqual(f.gets, []);
  }
  const f = fixture([value(30, 25)]);
  await assert.rejects(f.invoke({ artifactName: 'unrequested-name' }), /allowlist/);
  await assert.rejects(f.invoke({ uploadedArtifactId: '30;delete-all' }), /Invalid uploaded/);
  const wrongRun = fixture([value(30, 25, { workflow_run: { id: 699 } })]);
  await assert.rejects(wrongRun.invoke(), /exact current workflow run/);
  assert.deepEqual(f.deleted, []); assert.deepEqual(wrongRun.deleted, []);
});
