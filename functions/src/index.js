const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getAuth } = require('firebase-admin/auth');
const { onCall } = require('firebase-functions/v2/https');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { workspace } = require('./workspace');

initializeApp();
const service = workspace(getFirestore());
const region = 'asia-east1';

exports.workspace = onCall({ region }, request => {
  const auth = request.auth && { ...request.auth, lookupEmail: email => getAuth().getUserByEmail(email) };
  return service.call(auth, request.data);
});
exports.cleanupWorkspace = onDocumentCreated({
  document: 'cleanupJobs/{jobId}', region, retry: true, timeoutSeconds: 540
}, event => service.cleanup(event.params.jobId));
