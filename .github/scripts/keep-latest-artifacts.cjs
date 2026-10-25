'use strict';

// Run only after a successful upload has produced artifact-id. This helper
// deletes Actions artifacts only, never workflow runs, logs or release assets.
const ALLOWED_NAMES = new Set([
  'herdr-pocket-debug',
  'android-test-reports',
  'herdr-pocket-host-macos-x86_64',
  'herdr-pocket-host-macos-arm64',
  'herdr-pocket-host-linux-x86_64',
  'sphinx-html',
]);

function id(value, label) {
  const text = String(value);
  if (!/^[1-9][0-9]*$/.test(text) || !Number.isSafeInteger(Number(text))) {
    throw new Error(`Invalid ${label}`);
  }
  return Number(text);
}

function artifact(value, expectedName) {
  if (!value || value.name !== expectedName) {
    throw new Error('Artifact name changed or escaped the exact-name scope');
  }
  const artifactId = id(value.id, 'artifact ID');
  const created = Date.parse(value.created_at);
  if (!Number.isFinite(created) || typeof value.expired !== 'boolean') {
    throw new Error('Artifact creation time/expiration metadata is incomplete');
  }
  return { ...value, id: artifactId, created };
}

function newer(left, right) {
  return left.created > right.created ||
    (left.created === right.created && left.id > right.id);
}

function readOnlyEvent(context, fullName) {
  if (String(context.actor || '').toLowerCase() === 'dependabot[bot]') return true;
  // Do not introduce pull_request_target privilege to solve read-only PR tokens.
  if (context.eventName === 'pull_request_target') return true;
  if (context.payload && context.payload.pull_request) {
    const head = context.payload.pull_request.head;
    return !head || !head.repo || head.repo.fork === true || head.repo.full_name !== fullName;
  }
  return false;
}

module.exports = async function keepLatestArtifacts({
  github, context, core, artifactName, uploadedArtifactId,
}) {
  const report = { artifactName, kept: null, deleted: [], alreadyGone: [], expiredSkipped: [] };
  const info = message => { if (core && typeof core.info === 'function') core.info(message); };
  const warning = message => { if (core && typeof core.warning === 'function') core.warning(message); };
  const skip = reason => { info(`Artifact retention skipped: ${reason}`); return { ...report, skipped: reason }; };

  if (uploadedArtifactId === undefined || uploadedArtifactId === null || uploadedArtifactId === '') {
    return skip('no successful upload artifact-id');
  }
  if (!ALLOWED_NAMES.has(artifactName)) throw new Error('Artifact name is not in the explicit approved allowlist');
  if (!context || !context.repo || !context.repo.owner || !context.repo.repo) throw new Error('Repository context required');
  const { owner, repo } = context.repo;
  if (readOnlyEvent(context, `${owner}/${repo}`)) return skip('fork/Dependabot/untrusted PR event');
  const uploadedId = id(uploadedArtifactId, 'uploaded artifact ID');
  const runId = id(context.runId, 'current workflow run ID');
  const actions = github.rest.actions;

  let uploaded;
  try {
    uploaded = artifact((await actions.getArtifact({ owner, repo, artifact_id: uploadedId })).data, artifactName);
  } catch (error) {
    if (error.status === 404) return skip('uploaded artifact already removed by concurrent cleanup');
    throw error;
  }
  if (uploaded.id !== uploadedId) throw new Error('Uploaded artifact response ID changed');
  if (!uploaded.workflow_run || id(uploaded.workflow_run.id, 'upload workflow run ID') !== runId) {
    throw new Error('The successful upload must belong to this exact current workflow run');
  }
  if (uploaded.expired) return skip('uploaded artifact is expired');

  // paginate() follows every page. Do not depend on API ordering or prefix
  // matching, and deduplicate overlapping pages during concurrent uploads.
  const listed = await github.paginate(actions.listArtifactsForRepo, {
    owner, repo, name: artifactName, per_page: 100,
  });
  const scoped = new Map([[uploaded.id, uploaded]]);
  for (const value of listed) {
    if (value.name !== artifactName) continue;
    const current = artifact(value, artifactName);
    if (current.expired) { report.expiredSkipped.push(current.id); continue; }
    if (scoped.has(current.id)) {
      const previous = scoped.get(current.id);
      if (previous.created !== current.created || previous.name !== current.name) {
        throw new Error('Artifact immutable identity changed across API pages');
      }
    }
    scoped.set(current.id, current);
  }
  const values = [...scoped.values()];
  const keep = values.reduce((best, value) => newer(value, best) ? value : best);
  report.kept = keep.id;
  const obsolete = values.filter(value => value.id !== keep.id)
    .sort((left, right) => left.created - right.created || left.id - right.id);

  for (const candidate of obsolete) {
    // Strictly increasing replacement order prevents cooperating cleanup runs
    // from deleting every version. A later upload absent from this snapshot is
    // never a deletion candidate. Recheck the keeper immediately before DELETE.
    let replacement;
    try {
      replacement = artifact((await actions.getArtifact({ owner, repo, artifact_id: keep.id })).data, artifactName);
    } catch (error) {
      if (error.status === 404) return skip('newest replacement disappeared; preserving remaining artifacts');
      throw error;
    }
    if (replacement.id !== keep.id) throw new Error('Replacement artifact response ID changed');
    if (replacement.expired || !newer(replacement, candidate)) {
      return skip('no strictly newer live replacement; preserving remaining artifacts');
    }
    try {
      await actions.deleteArtifact({ owner, repo, artifact_id: candidate.id });
      report.deleted.push(candidate.id);
    } catch (error) {
      if (error.status === 404) { report.alreadyGone.push(candidate.id); continue; }
      warning(`Artifact retention stopped deleting older ${artifactName} artifacts: ${error.message || error}`);
      throw error; // Visible failure; never silently claim retention succeeded.
    }
  }
  info(`Kept newest ${artifactName} artifact ${report.kept}; deleted ${report.deleted.length} older artifact(s).`);
  return report;
};
